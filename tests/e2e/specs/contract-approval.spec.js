/**
 * 合同审批 · 端到端主链路
 *
 *   登录 → 点击发起 → 找到「合同审批」 → 按流程填表 → 提交 → 逐步审批 → 办结
 *
 * 这条用例同时把前两轮修掉的两个缺陷盖进回归网：
 *   ① 条件分支比较值口径（label → 码值）：逐级审批的第一步必须落在「经发部审批」，
 *      而不是报 `Error while evaluating expression: ${field101 == '经营'}`；
 *   ② 单据标题（getTitle 命中了「分组标题」这类无 vModel 的排版控件）：
 *      待办行与「我发起的」的标题必须等于夹具名（曾被算成 undefined、键被 JSON.stringify 丢掉）。
 *
 * 夹具：单据名带 `E2E` 前缀 + 时间戳，便于识别。
 * 账号：单账号（superAdmin）走完全链 —— 原因与恢复方式见 helpers/env.js 的 CONTRACT.actor 注释。
 *
 * ⚠ 已知限制：**每次运行会在库里留下 1 张真实单据**（form / todo / done / my_draft 各若干行，
 *   以及 Flowable 的历史实例）。收尾尚未自动化：删实例有产品接口
 *   `DELETE /flowable/instance/delete/{instanceIds}`，但业务表的清理判据需要单独设计
 *   （参见 DEV-ENV 里"错误的清理判据会让清理与验证清理同时失效"的教训）。
 *   当前依赖人工/批处理清理，或后续补 teardown。
 */
'use strict';

const { test, expect } = require('@playwright/test');
const { BASE_URL, AUTH_DIR, CONTRACT } = require('../helpers/env');
const { watchPage, assertNoBusinessFailure } = require('../helpers/assertions');

/** 夹具单据名（ASCII 前缀 + 时间戳，避免与既有数据重名） */
const FIXTURE_TITLE = `E2E合同审批-${Date.now()}`;
const COMMENT = 'E2E 自动化：同意';

/** 按账号开一个带登录态的 context */
async function contextFor(browser, user) {
  return browser.newContext({ baseURL: BASE_URL, storageState: `${AUTH_DIR}/${user}.json` });
}

/** 打开「合同审批」发起页并填完必填项 */
async function fillContractForm(page) {
  await page.getByPlaceholder('请输入合同名称').fill(FIXTURE_TITLE);
  await page.getByPlaceholder('请输入合同内容 / 主要条款摘要').fill('E2E 自动化用例：合同描述');
  await page.getByPlaceholder('请输入合同金额').fill('1234.56');
  await page.getByPlaceholder('请输入对方单位全称').fill('E2E对方单位');

  // 合同类型 = 经营（选项 value 为 1，是条件分支的判定字段）
  await page.getByText('经营', { exact: true }).first().click();

  // 日期选择器：输入后回车（value-format=YYYY-MM-DD）
  for (const [ph, val] of [['请选择开始时间', '2026-10-01'], ['请选择结束时间', '2026-12-31']]) {
    const box = page.getByPlaceholder(ph).first();
    await box.click();
    await box.fill(val);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');
  }
}

/** 点「提交」并把弹出的审批确认框点「确定」 */
async function submitAndConfirm(page, context) {
  await page.getByRole('button', { name: /提\s*交/ }).first().click();
  const dialog = page.locator('.el-dialog:visible').first();
  try {
    await expect(dialog, `[${context}] 提交后应弹出审批确认框`).toBeVisible({ timeout: 20_000 });
  } catch (e) {
    console.log(`[e2e][${context}] 未出现确认框，页面文本：` + (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400));
    throw e;
  }
  await dialog.getByRole('button', { name: /确\s*定/ }).click();
  await page.waitForTimeout(2500);
}

/**
 * 前置检查：账号能否打开「我的待办」。
 *
 * 2026-10-05 实测阻塞：`sys_role_menu` 是空表，`SecurityUtils.isAdmin` 又硬编码为
 * `userName == "superAdmin"`，于是非超管用户菜单/权限全空（/getRouters → []、接口 403、
 * `/my/todo` 直接 404）。这里 fail fast 并点出根因，避免"红了但看不出为什么"。
 */
async function assertCanOpenTodo(page, user) {
  await page.goto('/my/todo', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const body = await page.locator('body').innerText();
  if (/404\s*错误|找不到网页/.test(body)) {
    throw new Error(
      `账号 ${user} 打不开「我的待办」（路由未注册 → 404）。\n` +
      `根因：sys_role_menu 为空表，且 SecurityUtils.isAdmin 只认 userName=="superAdmin"，\n` +
      `      因此非超管用户的菜单/权限全为空（实测 /getRouters 返回 []、接口 403）。\n` +
      `修复方向：给相应角色授予「我的」菜单树（新启流程/待办/已办/我发起的/回收站）与对应权限点。`
    );
  }
  await expect(page.locator('.el-table, .el-empty').first(), `${user} 的待办页应正常渲染`).toBeVisible({ timeout: 15_000 });
}

/** 打开「我的待办」，返回标题匹配夹具的那一行 */
async function findTodoRow(page, title) {
  await page.goto('/my/todo', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const row = page.locator('.el-table__row', { hasText: title }).first();
  await expect(row, `待办中应出现「${title}」`).toBeVisible({ timeout: 20_000 });
  return row;
}

/**
 * 审批一次：打开待办 → 断言「当前环节」（可选）→ 填意见 → 提交 → 确定
 * @returns {Promise<string>} 该行的文本（含当前环节）
 */
async function approveOnce(page, title, expectedNode) {
  const row = await findTodoRow(page, title);
  const rowText = (await row.innerText()).replace(/\s+/g, ' ');
  console.log(`[e2e] 待办行：${rowText}`);

  if (expectedNode) {
    // 「当前环节」列绑的是 curNode —— 这一步同时验证条件分支的路由结果
    expect(rowText, `当前环节应为「${expectedNode}」`).toContain(expectedNode);
  }

  await row.click();
  await page.waitForURL(/pageType=1/, { timeout: 20_000 });
  await page.waitForTimeout(2500);

  // 审批页必须保留审批意见（只有纯拟稿阶段才屏蔽）
  const comment = page.locator('textarea[placeholder="请选择或输入意见..."]');
  await expect(comment, '审批页应显示审批意见输入框').toBeVisible({ timeout: 15_000 });
  await comment.fill(COMMENT);

  await submitAndConfirm(page, `审批-${expectedNode || ''}`);
  return rowText;
}

test.describe('合同审批 · 端到端主链路', () => {
  test('登录 → 发起 → 填表 → 条件分支 → 逐级审批 → 办结', async ({ browser }) => {
    const ctx = await contextFor(browser, CONTRACT.actor);
    const page = await ctx.newPage();
    const watch = watchPage(page);

    try {
      // ================= 0. 前置检查（fail fast，不产生业务数据） =================
      await assertCanOpenTodo(page, CONTRACT.actor);
      console.log(`[e2e] 前置检查通过：${CONTRACT.actor} 可打开待办`);

      // ================= 1. 发起 =================
      await page.goto('/my/newstart', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1500);
      await page.locator('.tip-name', { hasText: CONTRACT.templateName }).first().click();
      await page.waitForTimeout(3500);
      expect(page.url(), '应进入合同审批拟稿页').toContain('pageType=0');

      await fillContractForm(page);
      await submitAndConfirm(page, '发起');
      assertNoBusinessFailure(watch, '发起提交');
      console.log(`[e2e] 发起成功：${FIXTURE_TITLE}`);

      // ================= 2. 我发起的（验证标题缺陷已修） =================
      await page.goto('/my/apply', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      const myRow = page.locator('.el-table__row', { hasText: FIXTURE_TITLE }).first();
      await expect(myRow, '「我发起的」应出现该单据且标题非空').toBeVisible({ timeout: 20_000 });
      console.log('[e2e] 我发起的行：' + (await myRow.innerText()).replace(/\s+/g, ' '));

      // ================= 3. 逐级审批（按流程定义顺序） =================
      for (const node of CONTRACT.approvalChain) {
        await approveOnce(page, FIXTURE_TITLE, node);
        console.log(`[e2e] ✓ 已审批节点：${node}`);
      }
      assertNoBusinessFailure(watch, '逐级审批');

      // ================= 4. 终态 =================
      await page.goto('/my/todo', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      await expect(
        page.locator('.el-table__row', { hasText: FIXTURE_TITLE }),
        '办结后「我的待办」不应再有该单据'
      ).toHaveCount(0);

      await page.goto('/my/apply', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      const doneRow = page.locator('.el-table__row', { hasText: FIXTURE_TITLE }).first();
      await expect(doneRow).toBeVisible({ timeout: 20_000 });
      const doneText = (await doneRow.innerText()).replace(/\s+/g, ' ');
      console.log('[e2e] 终态「我发起的」行：' + doneText);
      expect(doneText, '单据标题应保持为夹具名').toContain(FIXTURE_TITLE);
      expect(doneText, '单据应已办结').toContain('已办结');

      assertNoBusinessFailure(watch, '全链路');
      console.log(`[e2e] ✅ 全链路办结：${FIXTURE_TITLE}`);
    } finally {
      await ctx.close();
    }
  });
});
