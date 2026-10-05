/**
 * B4 进销存 **UI 层用例骨架**（页面级细节留 t12 之后补，本文件不追求 UI 全绿）
 *
 * 本文件只做三件"结构上该由 E2E 守住"的事：
 *   1. **导航一律 `page.goto`** —— 技术债 D-6：本平台点侧边栏兄弟页不刷新内容（既有缺陷），
 *      用 `page.goto` 才能确保拿到的就是目标页；
 *   2. **列表/表单的结构断言**：页面可达（没被路由到 404）、`el-table` 表头含 `doc-kinds.js` 里配置的列名、
 *      列表有「新增<单据名>」入口、表单页有该单据的表头字段标签；
 *   3. **`el-table` 的点击用 Playwright 原生 click** —— 本机浏览器桥点不动 el-table 的行/复选框
 *      （DEV-ENV §6.53），E2E 里必须用原生 click，否则会误判成"页面坏了"。
 *
 * 期望值真源：`src/views/erp/doc/doc-kinds.js`（8 类单据的列/字段键）+ `sys_menu` 里的 10 条进销存菜单
 *   （列表 8 条 + `stock-balance` / `stock-ledger`）+ `stock-ledger/components/LedgerTable.vue` 的列标签。
 *
 * ⚠ t12 刚落地页面壳，细节（动作按钮权限、抽屉、下钻）由 t14 在真数据上补断言。
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const path = require('path');
const { test, expect } = require('@playwright/test');
const { AUTH_DIR } = require('../helpers/env');
const { createApi, okData } = require('../helpers/api');
const { ErpFixtures } = require('../helpers/erp-fixtures');
const { KINDS } = require('../../../src/views/erp/doc/doc-kinds.js');

test.use({ storageState: path.join(AUTH_DIR, 'superAdmin.json') });

/** 打开页面并等一会儿（RuoYi 页面的数据请求都挂在 created/mounted 上） */
async function gotoAndSettle(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
  // 没被路由到 404 页（RuoYi 的 404 会改 URL 到 /404）
  expect(page.url(), `${url} 不应被路由到 404`).not.toContain('/404');
}

test.describe('B4 进销存 UI 骨架（8 类单据）', () => {
  for (const kind of KINDS) {
    test(`UI · ${kind.label} 列表页：可达 + 表头列 + 新增入口`, async ({ page }) => {
      await gotoAndSettle(page, kind.listPath);

      // 页面壳（所有列表页共用的容器）
      await expect(page.locator('.app-container, .el-card, .el-table').first(), `${kind.label} 列表页应渲染`).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('.el-table').first(), `${kind.label} 应有表格`).toBeVisible();

      // 表头列：doc-kinds 里声明的前几列（真源就是它，别在用例里另抄一份）
      const header = page.locator('.el-table__header').first();
      const labels = kind.columns.slice(0, 4).map((c) => c.label);
      for (const label of labels) {
        await expect(header, `${kind.label} 表头应含列「${label}」`).toContainText(label);
      }

      // 新增入口（DocListShell 渲染「新增<单据名>」）
      await expect(
        page.getByRole('button', { name: new RegExp(`新\\s*增|新\\s*建`) }).first(),
        `${kind.label} 应有新增入口`
      ).toBeVisible({ timeout: 10_000 });
    });

    test(`UI · ${kind.label} 表单页：可达 + 表头字段标签`, async ({ page }) => {
      await gotoAndSettle(page, kind.formPath);
      await expect(page.locator('.app-container, .el-form').first(), `${kind.label} 表单页应渲染`).toBeVisible({ timeout: 15_000 });

      // 表头字段标签（取前 3 个：单据日期这类必填字段一定在）
      for (const field of kind.headerFields.slice(0, 3)) {
        await expect(page.locator('body'), `${kind.label} 表单应含字段「${field.label}」`).toContainText(field.label);
      }
    });
  }
});

test.describe('B4 进销存 UI 骨架（库存账两页）', () => {
  test('UI · 库存明细页：可达 + 结存/额度列', async ({ page }) => {
    await gotoAndSettle(page, '/erp/stock-balance');
    await expect(page.locator('.el-table').first(), '库存明细应有表格').toBeVisible({ timeout: 15_000 });
    const header = page.locator('.el-table__header').first();
    for (const label of ['商品类型-物料名称', '结存数量', '货品总额度']) {
      await expect(header, `库存明细表头应含列「${label}」`).toContainText(label);
    }
  });

  test('UI · 库存流水页：可达 + 流水列（业务类型/数量变动/变动后结存）', async ({ page }) => {
    await gotoAndSettle(page, '/erp/stock-ledger');
    await expect(page.locator('.el-table').first(), '库存流水应有表格').toBeVisible({ timeout: 15_000 });
    const header = page.locator('.el-table__header').first();
    for (const label of ['业务类型', '数量变动', '变动后结存', '单据号']) {
      await expect(header, `库存流水表头应含列「${label}」`).toContainText(label);
    }
  });
});

test.describe('B4 进销存 UI 骨架（交互方式）', () => {
  let api;
  let dispose;
  let fx;

  test.beforeAll(async () => {
    const created = await createApi('superAdmin');
    api = created.api;
    dispose = created.dispose;
    fx = new ErpFixtures(api, `E2E4U${Date.now().toString(36).toUpperCase()}`);
  });

  test.afterAll(async () => {
    if (fx) {
      const summary = await fx.cleanup();
      await fx.expectZeroResidue(summary);
    }
    if (dispose) await dispose();
  });

  test('UI · 列表行用原生 click 打开（先经接口建一张草稿单，再在页面上点中它）', async ({ page }) => {
    // 经接口建一张"可识别"的草稿采购申请单（页面里按单号搜到它才断言，不依赖既有数据）
    const F = await fx.provision();
    const pr = await fx.createDraft('purchase_request', {
      docDate: F.date,
      purpose: `${fx.tag} UI 行点击`,
      items: [{ productId: F.product.id, qty: 1, unitPrice: 1 }],
    }, '建 UI 用例的草稿单');
    const detail = await fx.detail('purchase_request', pr.id);

    await gotoAndSettle(page, '/erp/purchase-request');
    // 用单号过滤，确保目标行就在第一页
    const search = page.locator('input[placeholder*="单号"], input[placeholder*="关键字"]').first();
    if (await search.count()) {
      await search.fill(String(detail.docNo));
      await page.getByRole('button', { name: /搜\s*索/ }).first().click();
      await page.waitForTimeout(1500);
    }
    const row = page.locator('.el-table__row', { hasText: String(detail.docNo) }).first();
    await expect(row, `列表应出现该草稿单 ${detail.docNo}`).toBeVisible({ timeout: 20_000 });

    // ---- 原生 click（浏览器桥点不动 el-table，这里必须用 Playwright 自己点） ----
    // ① 若列表带选择列（el-table-column type="selection"），先点复选框
    const checkbox = row.locator('.el-checkbox').first();
    if (await checkbox.count()) {
      await checkbox.click();
      await expect(row.locator('.el-checkbox.is-checked'), '勾选后应是选中态').toBeVisible({ timeout: 5_000 });
      await checkbox.click(); // 取消勾选，避免影响后续动作
    } else {
      // ② 没有选择列时，点「单号」链接进入详情/表单（同样必须是原生 click）
      await row.locator('a, .el-link, .el-button').first().click();
      await page.waitForTimeout(1500);
      await expect(page.locator('.el-form, .el-table').first(), '点击行后应进入详情/表单').toBeVisible({ timeout: 15_000 });
    }
    console.log(`[E2E4U] 原生点击行成功：${detail.docNo}（${await checkbox.count() ? '选择列' : '单号链接'}）`);
  });
});
