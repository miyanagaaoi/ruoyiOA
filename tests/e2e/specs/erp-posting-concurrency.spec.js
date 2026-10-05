/**
 * B4 并发过账 + 结存一致性（HTTP 层 E2E）
 *
 *   两张出库单**同时**审核打同一个 (物料, 仓库)：
 *     · 两次都成功（不是一张成功一张报死锁/丢更新）；
 *     · 终态 `结存 == 初始 + SUM(流水数量变动)`（不允许"更新丢失"，也不允许结存被覆盖成单张单的值）；
 *     · `POST /stk/stock/recalc` 在本夹具 key 上无 mismatch（该比较**在 SQL 里**算，等价 DB 终态不变式），
 *       全局 `inconsistentCount/mismatches` 照实打印（别的门禁脚本在飞的夹具会造成运行期派生差异，队长裁决）。
 *
 * 为什么这条必须存在：`notes/03-posting.md` §5（AC-74）明确要"行锁 + 排序取锁 + 唯一键重试"，
 *   而内存桩单测只能证明"桩里的锁区间"，**证明不了真实 MySQL 上的行锁行为** —— 只有 HTTP 并发才作数。
 *
 * 端点真源：`ErpStockInController`/`ErpStockOutController`（`POST /approve/{id}`）、
 *   `ErpStockBalanceController`（`POST /stk/stock/recalc?repair=`）、`GET /stk/stock/list`、`GET /stk/ledger/list`。
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const { test, expect } = require('@playwright/test');
const { createApi, okData, okList } = require('../helpers/api');
const { ErpFixtures, recalcScoped } = require('../helpers/erp-fixtures');

const TAG = `E2E4C${Date.now().toString(36).toUpperCase()}`;

let api, dispose, fx, F;

async function qtyOf(productId, warehouseId) {
  const { rows } = await okList(api, await api.get('/stk/stock/list', { productId, warehouseId, pageSize: 50 }), '库存明细');
  return rows.length ? Number(rows[0].qty) : 0;
}
async function ledgerSum(productId, warehouseId) {
  const { rows } = await okList(api, await api.get('/stk/ledger/list', { productId, warehouseId, pageSize: 500 }), '库存流水');
  return { sum: rows.reduce((s, r) => s + Number(r.qtyChange), 0), rows };
}
async function seedStock(productId, warehouseId, qty, unitPrice = 1) {
  const sin = await fx.createDraft('stock_in', {
    docDate: F.date, warehouseId, inType: '采购入库',
    items: [{ productId, qty, unitPrice }],
  }, '备货入库单');
  await okData(api, await fx.act('stock_in', sin.id, 'submit'), '提交备货入库');
  await fx.approve('stock_in', sin.id);
  return sin;
}
async function draftOut(qty) {
  const sout = await fx.createDraft('stock_out', {
    docDate: F.date, warehouseId: F.whA.id, outType: '其他出库',
    items: [{ productId: F.product.id, qty, unitPrice: 1 }],
  }, `建出库单（并发用例，qty=${qty}）`);
  await okData(api, await fx.act('stock_out', sout.id, 'submit'), '提交出库单');
  return sout;
}

test.describe('B4 并发过账与结存一致性（HTTP 层）', () => {
  test.beforeAll(async () => {
    const created = await createApi('superAdmin');
    api = created.api;
    dispose = created.dispose;
    fx = new ErpFixtures(api, TAG);
    F = await fx.provision();
    console.log(`[E2E4C] 夹具就绪 tag=${TAG} 物料=${F.product.code} 仓库=${F.whA.code}`);
  });

  test.afterAll(async () => {
    if (fx) {
      const summary = await fx.cleanup();
      await fx.expectZeroResidue(summary);
    }
    if (dispose) await dispose();
  });

  test('两张出库单并发审核：都成功、无更新丢失、结存 == Σ流水', async () => {
    await seedStock(F.product.id, F.whA.id, 100, 1);
    const start = await qtyOf(F.product.id, F.whA.id);
    expect(start, '并发用例起点结存').toBeCloseTo(100, 3);

    const d1 = await draftOut(30);
    const d2 = await draftOut(30);

    // 真并发：两个 approve 同时在飞
    const [r1, r2] = await Promise.all([
      fx.approve('stock_out', d1.id),
      fx.approve('stock_out', d2.id),
    ]);
    expect(r1.body.code, `第一张审核应成功（msg=${r1.body.msg}）`).toBe(200);
    expect(r2.body.code, `第二张审核应成功（msg=${r2.body.msg}）`).toBe(200);

    const after = await qtyOf(F.product.id, F.whA.id);
    const { sum, rows } = await ledgerSum(F.product.id, F.whA.id);
    expect(after, '两次扣减都要生效（不是 70 那种更新丢失）').toBeCloseTo(40, 3);
    expect(after, '结存 == Σ流水数量变动').toBeCloseTo(sum, 3);
    expect(rows.filter((r) => r.qtyChange < 0).length, '应有两笔出库流水').toBeGreaterThanOrEqual(2);

    // 重算一致性：按自己夹具的 key 断言；全局数字照实打印（可能是别的门禁脚本在飞的夹具）
    const recalc = await recalcScoped(api, [{ productId: F.product.id, warehouseId: F.whA.id }]);
    expect(recalc.repair, '默认只读（repair=false）').toBe(false);

    // 反审核其中一张：结存回来且不变式仍成立
    await okData(api, await fx.act('stock_out', d1.id, 'unapprove', { reason: `${TAG} 并发用例` }), '反审核第一张');
    const afterReverse = await qtyOf(F.product.id, F.whA.id);
    const sum2 = (await ledgerSum(F.product.id, F.whA.id)).sum;
    expect(afterReverse, '红冲一张 ⇒ 70').toBeCloseTo(70, 3);
    expect(afterReverse, '红冲后仍等于 Σ流水').toBeCloseTo(sum2, 3);
  });

  test('recalc 默认只读：repair=false 不改任何数据、且本夹具无不一致（全局差异照实打印）', async () => {
    const before = await qtyOf(F.product.id, F.whA.id);
    const recalc = await recalcScoped(api, [{ productId: F.product.id, warehouseId: F.whA.id }]);
    expect(recalc.repair, 'repair 回显 false').toBe(false);
    expect(recalc.repairedCount, '只读模式不应修复任何行').toBe(0);
    expect(Number.isInteger(recalc.checkedRows), 'checkedRows 应是整数').toBe(true);
    // 本夹具已备货时，必须真的检查到了那行（单独跑本用例时库里可能一行结存都没有 ⇒ 不硬要求 >0）
    if (before > 0) {
      expect(recalc.checkedRows, '夹具已有结存 ⇒ 至少检查到它').toBeGreaterThan(0);
    }
    expect(await qtyOf(F.product.id, F.whA.id), '只读模式不得改动结存').toBeCloseTo(before, 3);
  });

  test('审核幂等：同一张单重复审核不产生新流水、不改结存', async () => {
    const d = await draftOut(5);
    await fx.approve('stock_out', d.id);
    const after1 = await qtyOf(F.product.id, F.whA.id);
    const docNo = (await fx.detail('stock_out', d.id)).docNo;
    const rows1 = (await ledgerSum(F.product.id, F.whA.id)).rows.filter((r) => r.docNo === docNo).length;
    await fx.approve('stock_out', d.id); // 再审核一次
    expect(await qtyOf(F.product.id, F.whA.id), '重复审核不改结存').toBeCloseTo(after1, 3);
    const rows2 = (await ledgerSum(F.product.id, F.whA.id)).rows.filter((r) => r.docNo === docNo).length;
    expect(rows2, '重复审核不产生新流水').toBe(rows1);
  });
});
