/**
 * B4 **UI 写路径**（入库单）：表单页填行项 → 保存草稿 → 提交 → 列表审核（过账）→ 反审核红冲
 *
 * ⚠ 用例归属说明（验收追溯矩阵要用）：
 *   本条原属 **t12 的交付项**「逐页浏览器走通 保存/提交/审核/红冲/下推」，因后端 **C-1**
 *   （`ErpDocHeader` 的 `posted` 歧义 getter ⇒ 8 类单据写路径 500，由 t31 修复）而被阻塞，
 *   队长裁决**并入 t14**。其余 7 类单据的写路径由同目录的 HTTP 链路用例覆盖（不重复走 UI）。
 *
 * 断言三层（本条三层都有）：
 *   ① UI 可观察：单据金额随行项变化、保存/提交/审核/反审核的成功提示、提交后表单进入锁定态
 *      （`当前状态（待审核）不可编辑`）、列表行的状态标签（草稿 → 待审核 → 已审核 → 待审核）；
 *   ② 接口/DB 终态：审核后 `status=approved`、`posted='1'`、结存 +3；反审核后 `submitted`、`posted='0'`、
 *      结存回位，且**结存 == Σ流水数量变动**（流水只增不改：原 1 条 + 红冲 1 条）；
 *   ③ 关键口径：单据金额 = 逐行「数量 × 单价」先舍入到分再汇总（3 × 2.5 = 7.50）。
 *
 * 实现说明（为什么这样点）：
 *   · 导航一律 `page.goto`（技术债 D-6：点侧边栏兄弟页不刷新内容）；
 *   · `el-select` 的选项渲染在 `body` 下的 `.el-select-dropdown`（不是行内 DOM），用 `:visible` 定位；
 *   · `el-input-number` 需要 `fill` + `Tab`（失焦才触发 change）；
 *   · `el-table` 的行内按钮用 Playwright 原生 click（浏览器桥点不动，DEV-ENV §6.53）。
 *
 * @author 二开（t14；用例原属 t12，因 C-1 阻塞并入）
 */
'use strict';

const path = require('path');
const { test, expect } = require('@playwright/test');
const { AUTH_DIR } = require('../helpers/env');
const { createApi, okData, okList } = require('../helpers/api');
const { ErpFixtures } = require('../helpers/erp-fixtures');

test.use({ storageState: path.join(AUTH_DIR, 'superAdmin.json') });

let api, dispose, fx, F;

async function settle(page, ms = 1800) {
  await page.waitForTimeout(ms);
}

/** 打开一个 el-select 下拉并选中文案匹配的选项（下拉挂在 body 下，与触发的 select 不是父子） */
async function pickOption(page, scope, optionText) {
  await scope.locator('.el-select input, input').first().click();
  const dd = page.locator('.el-select-dropdown:visible').first();
  await expect(dd, `下拉应展开（选项「${optionText}」）`).toBeVisible({ timeout: 10_000 });
  const opt = dd.locator('.el-select-dropdown__item', { hasText: optionText }).first();
  await expect(opt, `下拉里应有选项「${optionText}」`).toBeVisible({ timeout: 10_000 });
  await opt.click();
  await page.waitForTimeout(300);
}

/** 列表页按单号过滤出目标行 */
async function filterRow(page, listPath, docNo) {
  await page.goto(listPath, { waitUntil: 'domcontentloaded' });
  await settle(page);
  const kw = page.locator('input[placeholder*="单号"], input[placeholder*="关键字"]').first();
  if (await kw.count()) {
    await kw.fill(String(docNo));
    await page.getByRole('button', { name: /搜\s*索/ }).first().click();
    await settle(page, 1500);
  }
  const row = page.locator('.el-table__row', { hasText: String(docNo) }).first();
  await expect(row, `列表应出现单据 ${docNo}`).toBeVisible({ timeout: 20_000 });
  return row;
}

/** 在动作弹窗里点确定（驳回/作废/反审核需要先填原因） */
async function confirmDialog(page, { reason, expectTip } = {}) {
  const dlg = page.locator('.el-dialog:visible').first();
  await expect(dlg, '动作弹窗应出现').toBeVisible({ timeout: 15_000 });
  if (reason) {
    const box = dlg.locator('textarea').first();
    await expect(box, '驳回/作废/反审核弹窗应有原因输入框').toBeVisible({ timeout: 10_000 });
    await box.fill(reason);
  } else if (expectTip) {
    await expect(dlg, '弹窗应给出审核即过账的提示').toContainText(expectTip);
  }
  await dlg.getByRole('button', { name: /确\s*定/ }).click();
  await settle(page, 1500);
}

async function stockQty(productId, warehouseId) {
  const { rows } = await okList(api, await api.get('/stk/stock/list', { productId, warehouseId, pageSize: 50 }), '库存明细');
  return rows.length ? Number(rows[0].qty) : 0;
}

test.describe('B4 UI 写路径（入库单）· 保存 → 提交 → 审核 → 反审核红冲', () => {
  test.beforeAll(async () => {
    const created = await createApi('superAdmin');
    api = created.api;
    dispose = created.dispose;
    fx = new ErpFixtures(api, `E2E4U${Date.now().toString(36).toUpperCase()}`);
    F = await fx.provision();
    console.log(`[E2E4U-W] UI 写路径夹具：tag=${fx.tag} 物料=${F.product.code} 仓库=${F.whA.code}`);
  });

  test.afterAll(async () => {
    if (fx) {
      const summary = await fx.cleanup();
      await fx.expectZeroResidue(summary);
    }
    if (dispose) await dispose();
  });

  test('UI · 入库单：保存草稿 → 提交 → 审核过账 → 反审核红冲（页面与后端一致）', async ({ page }) => {
    const qtyBefore = await stockQty(F.product.id, F.whA.id);

    /* ---------------- 1. 新增：打开表单页（goto，不走侧边栏） ---------------- */
    await page.goto('/erp/stock-in/form?mode=add', { waitUntil: 'domcontentloaded' });
    await settle(page);
    await expect(page.locator('.erp-doc-form'), '入库单表单页应渲染').toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.el-page-header__content, .el-page-header').first()).toContainText('入库单');

    /* ---------------- 2. 填表头：日期 / 仓库 / 入库类型 ---------------- */
    const dateInput = page.locator('.el-form-item', { hasText: '单据日期' }).locator('input').first();
    await dateInput.click();
    await dateInput.fill(F.date);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');

    await pickOption(page, page.locator('.el-form-item', { hasText: '仓库' }).first(), F.whA.name);
    await pickOption(page, page.locator('.el-form-item', { hasText: '入库类型' }).first(), '采购入库');
    // ⚠ 断言**必须**落在 `input` 的 value 上：el-select 的选中文本在 `<input>` 的 value 里，
    //   容器的 `innerText` **不含** input 的 value（run 7 实测：ARIA 快照为
    //   `textbox "请选择仓库": E2E4…一号仓` 证明已选中，而 `.el-form-item` 的 innerText 只有 label「仓库」）
    await expect(
      page.locator('.el-form-item', { hasText: '仓库' }).first().locator('input'),
      '仓库应回显所选仓库名'
    ).toHaveValue(F.whA.name);
    await expect(
      page.locator('.el-form-item', { hasText: '入库类型' }).first().locator('input'),
      '入库类型应回显所选字典项'
    ).toHaveValue('采购入库');

    /* ---------------- 3. 行项：添加行 → 选物料 → 数量 3 → 单价 2.5 ---------------- */
    await page.getByRole('button', { name: '添加行项' }).click();
    await settle(page, 500);
    const row = page.locator('.doc-items-table .el-table__body .el-table__row').first();
    await expect(row, '应新增一行行项').toBeVisible();

    // 物料（行内第 1 个 select；label = 编码 · 名称）
    await pickOption(page, row.locator('.el-select').first(), F.product.code);
    await expect(row, '选物料后应带出物料编码快照').toContainText(F.product.code);
    await expect(row, '选物料后应带出单位快照').toContainText(F.uom3.name);

    // 交付证据（DOM 级）：把三处下拉的**当前值**与行项「单位」单元格文本、以及行项快照 uomDecimals 打进日志
    // （P-②/P-④/P-⑤ 三条修复的直接证据；通过运行不产 error-context，所以显式打印）
    const domEvidence = {
      仓库: await page.locator('.el-form-item', { hasText: '仓库' }).first().locator('input').inputValue(),
      入库类型: await page.locator('.el-form-item', { hasText: '入库类型' }).first().locator('input').inputValue(),
      物料: await row.locator('.el-select input').first().inputValue(),
      单位单元格: (await row.locator('td').nth(4).innerText()).trim(),
      uomDecimals: await page.evaluate(() => {
        const vm = document.querySelector('.erp-doc-form').__vue__;
        return ((vm.items || [])[0] || {}).uomDecimals;
      }),
    };
    console.log('[E2E4U-W][DOM 证据] ' + JSON.stringify(domEvidence));

    // 数量与单价（el-input-number 失焦才触发 change）
    const nums = row.locator('.el-input-number input');
    await nums.nth(0).fill('3');
    await nums.nth(0).press('Tab');
    await nums.nth(1).fill('2.5');
    await nums.nth(1).press('Tab');
    await settle(page, 500);
    // ③ 口径：行金额/单据金额 = 先舍入再汇总 ⇒ 7.50
    await expect(page.locator('.items-total'), '单据金额应为 3 × 2.5 = 7.50').toContainText('7.50');

    /* ---------------- 4. 保存草稿 ---------------- */
    await page.getByRole('button', { name: '保存草稿' }).click();
    // ⚠ 提示文案必须**按内容定位**：Element UI 会把多条 message 同时留在 DOM 里（旧的还没淡出），
    //   用 `.first()` 可能一直取到上一条（run9 实测：提交成功后 `.first()` 仍是「保存成功」）
    await expect(
      page.locator('.el-message--success', { hasText: '保存成功' }).first(),
      '保存应有成功提示'
    ).toBeVisible({ timeout: 20_000 });
    await page.waitForURL(/id=[A-Za-z0-9]+/, { timeout: 20_000 });
    const docId = new URL(page.url()).searchParams.get('id');
    expect(docId, '保存后地址栏应带单据 id').toBeTruthy();
    const afterSave = await fx.detail('stock_in', docId);
    fx.trackDoc('stock_in', afterSave); // ② 抓到 id，收尾按业务接口清理
    expect(afterSave.status, '保存后应为草稿').toBe('draft');
    expect(String(afterSave.docNo), '单号形状').toMatch(/^[A-Z]{2}\d{12}$/);
    expect(Number(afterSave.totalAmount), '后端金额与页面一致').toBeCloseTo(7.5, 2);
    expect(await stockQty(F.product.id, F.whA.id), '草稿未过账 ⇒ 结存不变').toBeCloseTo(qtyBefore, 3);
    const firstItems = await fx.items('stock_in', docId);
    expect(Number(firstItems[0].qty), '行项数量落库').toBeCloseTo(3, 3);
    expect(Number(firstItems[0].unitPrice), '行项单价落库').toBeCloseTo(2.5, 4);

    /* ---------------- 5. 保存并提交 → 表单进入锁定态 ---------------- */
    await page.getByRole('button', { name: '保存并提交' }).click();
    await expect(
      page.locator('.el-message--success', { hasText: '已提交' }).first(),
      '提交应有成功提示'
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.form-lock'), '提交后表单应锁定并提示当前状态').toContainText('不可编辑', { timeout: 15_000 });
    await expect(page.locator('.form-lock')).toContainText('待审核');
    expect((await fx.detail('stock_in', docId)).status, '后端状态已提交').toBe('submitted');

    /* ---------------- 6. 列表页：审核（审核即过账） ---------------- */
    // 分三步断言（可见 → **启用** → 点击）：这样失败信息能直接区分"按钮没渲染"与"按钮被禁用"，
    // 而不是笼统地 `locator.click timeout`。注意：`toBeEnabled` 是**硬断言**——按钮被禁用时照样红。
    const rowApprove = await filterRow(page, '/erp/stock-in', afterSave.docNo);
    // ⚠ 操作列 `fixed="right"`：同一行的按钮会被渲染**两份**（主表 + `.el-table__fixed-right` 覆盖层），
    //   两份都可能"可见" ⇒ `:visible` 过滤或 `.or()` 都会撞上 strict mode（run12 实测：or() 解析到 2 个元素）。
    //   因此**按存在性确定性地二选一**（优先固定列那份——它在上层、可点），仍然要求"可见 + 未禁用"。
    const docNoApproving = afterSave.docNo;
    const approveFixed = page
      .locator('.el-table__fixed-right:visible .el-table__row', { hasText: docNoApproving })
      .locator('button', { hasText: /^审\s*核$/ })
      .first();
    const approveBtn = (await approveFixed.count())
      ? approveFixed
      : page.locator('.el-table__row', { hasText: docNoApproving }).locator('button', { hasText: /^审\s*核$/ }).first();
    await expect(approveBtn, '列表中应有「审核」按钮（且可见）').toBeVisible({ timeout: 20_000 });
    await expect(approveBtn, '「审核」按钮不应被禁用').toBeEnabled();
    await approveBtn.click();
    await confirmDialog(page, { expectTip: '审核即过账' });
    await expect(page.locator('.el-message--success', { hasText: '审核' }).first(), '审核应成功').toBeVisible({ timeout: 20_000 });
    await settle(page, 1500);

    const approved = await fx.detail('stock_in', docId);
    expect(approved.status, '② 审核后状态').toBe('approved');
    expect(String(approved.posted), '② 审核即过账').toBe('1');
    expect(await stockQty(F.product.id, F.whA.id), '② 结存 +3').toBeCloseTo(qtyBefore + 3, 3);
    // ① 页面状态与后端一致
    await expect(
      page.locator('.el-table__row', { hasText: afterSave.docNo }).first(),
      '列表行应显示已审核'
    ).toContainText('已审核', { timeout: 15_000 });

    const ledger1 = await okList(api, await api.get('/stk/ledger/list', { productId: F.product.id, warehouseId: F.whA.id, pageSize: 100 }), '库存流水');
    const mine1 = ledger1.rows.filter((r) => r.docNo === approved.docNo);
    expect(mine1.length, '过账应产生 1 条本单流水').toBe(1);
    expect(Number(mine1[0].qtyChange), '流水 +3').toBeCloseTo(3, 3);
    expect(mine1[0].bizType, '流水业务类型').toBe('采购入库');
    expect(Number(mine1[0].qtyAfter), '流水变动后结存快照').toBeCloseTo(qtyBefore + 3, 3);

    /* ---------------- 7. 列表页：反审核（红冲，原因必填） ---------------- */
    const rowUnapprove = await filterRow(page, '/erp/stock-in', approved.docNo);
    const docNoReversing = approved.docNo;
    const unapproveFixed = page
      .locator('.el-table__fixed-right:visible .el-table__row', { hasText: docNoReversing })
      .locator('button', { hasText: /^反\s*审\s*核$/ })
      .first();
    const unapproveBtn = (await unapproveFixed.count())
      ? unapproveFixed
      : page.locator('.el-table__row', { hasText: docNoReversing }).locator('button', { hasText: /^反\s*审\s*核$/ }).first();
    await expect(unapproveBtn, '列表中应有「反审核」按钮（且可见）').toBeVisible({ timeout: 20_000 });
    await expect(unapproveBtn, '「反审核」按钮不应被禁用').toBeEnabled();
    await unapproveBtn.click();
    await confirmDialog(page, { reason: `E2E4U UI 红冲 ${fx.tag}` });
    await settle(page, 1500);

    const reversed = await fx.detail('stock_in', docId);
    expect(reversed.status, '② 反审核后回到待审核').toBe('submitted');
    expect(String(reversed.posted), '② 过账标记清 0').toBe('0');
    expect(await stockQty(F.product.id, F.whA.id), '② 红冲后结存回位').toBeCloseTo(qtyBefore, 3);
    await expect(
      page.locator('.el-table__row', { hasText: approved.docNo }).first(),
      '列表行应显示待审核'
    ).toContainText('待审核', { timeout: 15_000 });

    const ledger2 = await okList(api, await api.get('/stk/ledger/list', { productId: F.product.id, warehouseId: F.whA.id, pageSize: 100 }), '库存流水');
    const mine2 = ledger2.rows.filter((r) => r.docNo === approved.docNo);
    expect(mine2.length, '② 流水只增不改：原 1 条 + 红冲 1 条').toBe(2);
    expect(mine2.some((r) => String(r.bizType).startsWith('红冲-')), '应有红冲流水').toBe(true);
    const sum = ledger2.rows.reduce((s, r) => s + Number(r.qtyChange), 0);
    expect(await stockQty(F.product.id, F.whA.id), '② 结存 == Σ流水数量变动').toBeCloseTo(sum, 3);

    console.log(`[E2E4U-W] UI 写路径闭环成功：${approved.docNo}（草稿→待审核→已审核→待审核，红冲后结存回 ${qtyBefore}）`);
  });
});
