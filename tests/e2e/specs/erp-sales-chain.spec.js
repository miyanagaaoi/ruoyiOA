/**
 * B4 销售链路 · HTTP 层 E2E
 *
 *   申请 → 提交 → 审核 → 下推销售订单 → 审核 →（下推出库单）→ 审核过账 → 结存减少
 *
 * ⚠ **一处已知未落地（不掩盖、不跳过）**：`销售订单 → 出库单` 的下推端点**尚未实现**（t8 未完成）：
 *   前端 `doc-rules.js` 的 `PUSH_CHAINS.sales_order.push` 明确标着 `pending: true`，
 *   `ErpSalesController` 里只有 `sal:request:push`（申请→订单），没有 `sal:order/{id}/push*`。
 *   因此本文件分两部分：
 *     ① 可跑通的链路（申请 → 订单 → 审核 → 手工建出库单 → 过账 → 结存减少）—— 现在就应全绿；
 *     ② **契约预期的** 订单→出库单下推用例：按 `PUSH_CHAINS.sales_order` 的 `pathSuffix:'/push'` 写断言，
 *        t8 落地后应直接变绿；未落地时它必须**红**（t14 的报告据此区分"未部署"与"代码坏"），
 *        绝不用 skip 把它藏起来。
 *
 * 端点真源：`ErpSalesController`（`/sal/request*`、`/sal/order*`）、`ErpStockOutController`（`/stk/out-order*`）、
 *   `ErpStockBalanceController`（`/stk/stock/list`）；口径见 notes/05a-sales.md §4/§5、notes/03-posting.md §8。
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const { test, expect } = require('@playwright/test');
const { createApi, okData, okList, bizError } = require('../helpers/api');
const { ErpFixtures } = require('../helpers/erp-fixtures');

const TAG = `E2E4S${Date.now().toString(36).toUpperCase()}`;
const DOC_NO_RE = /^[A-Z]{2}\d{12}$/;

let api, dispose, fx, F;

/** 结存查询（(物料, 仓库) 的净数量） */
async function stockQty(fx, api, productId, warehouseId) {
  const { rows } = await okList(api, await api.get('/stk/stock/list', { productId, warehouseId, pageSize: 50 }), '库存明细');
  return rows.length ? Number(rows[0].qty) : 0;
}

test.describe('B4 销售链路（HTTP 层）', () => {
  test.beforeAll(async () => {
    const created = await createApi('superAdmin');
    api = created.api;
    dispose = created.dispose;
    fx = new ErpFixtures(api, TAG);
    F = await fx.provision();
    console.log(`[E2E4S] 夹具就绪 tag=${TAG} 物料=${F.product.code} 仓库=${F.whA.code} 客户=${F.customer.code}`);
  });

  test.afterAll(async () => {
    if (fx) {
      const summary = await fx.cleanup();
      await fx.expectZeroResidue(summary);
    }
    if (dispose) await dispose();
  });

  test('备货：一张采购入库单过账（给出库提供可用结存）', async () => {
    const sin = await fx.createDraft('stock_in', {
      docDate: F.date,
      warehouseId: F.whA.id,
      inType: '采购入库',
      items: [{ productId: F.product.id, qty: 20, unitPrice: 2 }],
    }, '建备货入库单');
    await okData(api, await fx.act('stock_in', sin.id, 'submit'), '提交入库单');
    await okData(api, await fx.approve('stock_in', sin.id), '审核入库单（备货）');
    expect(await stockQty(fx, api, F.product.id, F.whA.id), '备货后结存 20').toBeCloseTo(20, 3);
    // 备货单本身在 afterAll 里反审核红冲 + 作废
    
  });

  test('申请 → 审核 → 下推订单 → 订单审核 → 出库过账 → 结存减少', async () => {
    const before = await stockQty(fx, api, F.product.id, F.whA.id);

    // ---------- 1. 销售申请（多行、小数单价） ----------
    const sr = await fx.createDraft('sales_request', {
      docDate: F.date,
      expectDeliveryDate: F.date,
      customerId: F.customer.id,
      handlerName: `${TAG}经办`,
      items: [
        { productId: F.product.id, qty: 5, unitPrice: 9.5 },
        { productId: F.product0.id, qty: 3, unitPrice: 0.125 },
      ],
    }, '建销售申请单');
    const srDetail = await fx.detail('sales_request', sr.id);
    expect(srDetail.status).toBe('draft');
    expect(String(srDetail.docNo)).toMatch(DOC_NO_RE);
    await okData(api, await fx.act('sales_request', sr.id, 'submit'), '提交销售申请');
    await okData(api, await fx.approve('sales_request', sr.id), '审核销售申请');
    expect((await fx.detail('sales_request', sr.id)).status).toBe('approved');

    // ---------- 2. 下推销售订单（不传 lines = 按剩余量全推） ----------
    const push = await api.post(`/sal/request/${sr.id}/push`, {
      docDate: F.date,
      remark: `${TAG} 下推`,
      shipWarehouseId: F.whA.id,
    });
    const pushData = await okData(api, push, '下推销售订单');
    expect(pushData.sourceDocNo, '下推结果回来源单号').toBe(srDetail.docNo);
    const soId = pushData.orderId || (pushData.order && pushData.order.id);
    expect(soId, '下推应返回新订单 id（orderId）').toBeTruthy();
    const soDetail = await fx.detail('sales_order', soId);
    fx.trackDoc('sales_order', soDetail);
    expect(soDetail.status, '下推生成草稿订单').toBe('draft');
    expect(soDetail.shipWarehouseId, '应带过发货仓库').toBe(F.whA.id);
    expect(pushData.requestCompleted, '全部行项推完 ⇒ 申请单完成').toBe(true);

    // ---------- 3. 订单 提交 → 审核 ----------
    await okData(api, await fx.act('sales_order', soId, 'submit'), '提交销售订单');
    await okData(api, await fx.approve('sales_order', soId), '审核销售订单');
    expect((await fx.detail('sales_order', soId)).status).toBe('approved');

    // ---------- 4. 出库单：手工建（下推端点待 t8）→ 过账 ----------
    const sout = await fx.createDraft('stock_out', {
      docDate: F.date,
      warehouseId: F.whA.id,
      outType: '销售出库',
      customerId: F.customer.id,
      sourceDocType: 'sales_order',
      sourceDocId: soId,
      sourceDocNo: soDetail.docNo,
      items: [{ productId: F.product.id, qty: 5, unitPrice: 9.5 }],
    }, '建出库单（销售链路）');
    await okData(api, await fx.act('stock_out', sout.id, 'submit'), '提交出库单');
    await okData(api, await fx.approve('stock_out', sout.id), '审核出库单（过账）');
    const soutDetail = await fx.detail('stock_out', sout.id);
    expect(soutDetail.status).toBe('approved');
    expect(String(soutDetail.posted), '审核即过账').toBe('1');

    // ---------- 5. 结存减少 + 流水 + 不变式 ----------
    expect(await stockQty(fx, api, F.product.id, F.whA.id), '出库 5 ⇒ 结存减少 5').toBeCloseTo(before - 5, 3);
    const { rows: ledger } = await okList(api, await api.get('/stk/ledger/list', {
      productId: F.product.id, warehouseId: F.whA.id, pageSize: 100,
    }), '库存流水');
    const outRows = ledger.filter((r) => r.docNo === soutDetail.docNo);
    expect(outRows.length, '应有一条本单流水').toBe(1);
    expect(Number(outRows[0].qtyChange), '出库为负').toBeCloseTo(-5, 3);
    expect(outRows[0].bizType, '业务类型取 outType').toBe('销售出库');
    const sum = ledger.reduce((s, r) => s + Number(r.qtyChange), 0);
    expect(await stockQty(fx, api, F.product.id, F.whA.id), '结存 == Σ流水').toBeCloseTo(sum, 3);

    // 出库是"入库"以外的业务类型 ⇒ 不影响货品总额度（额度只认「入库」子串）
    const { rows: stockRows } = await okList(api, await api.get('/stk/stock/list', {
      productId: F.product.id, warehouseId: F.whA.id, pageSize: 50,
    }), '库存明细');
    expect(Number(stockRows[0].goodsQuota), '出库不改变额度（只有备货入库计入）').toBeCloseTo(20 * 2, 2);
  });

  test('出库负库存被拦：状态与结存都不变、不产生流水', async () => {
    const cur = await stockQty(fx, api, F.product.id, F.whA.id);
    const sout = await fx.createDraft('stock_out', {
      docDate: F.date,
      warehouseId: F.whA.id,
      outType: '销售出库',
      items: [{ productId: F.product.id, qty: cur + 100, unitPrice: 1 }],
    }, '建超量出库单');
    await okData(api, await fx.act('stock_out', sout.id, 'submit'), '提交超量出库单');
    const bad = await fx.approve('stock_out', sout.id);
    await bizError(api, bad, '超量出库过账', /可用量/);
    const after = await fx.detail('stock_out', sout.id);
    expect(after.status, '被拒后状态不变').toBe('submitted');
    expect(String(after.posted), '被拒后未过账').toBe('0');
    expect(await stockQty(fx, api, F.product.id, F.whA.id), '被拒后结存不变').toBeCloseTo(cur, 3);
  });

  test('销售订单 → 出库单 下推（**依赖 t8 落地**：端点未实现时这条必须红）', async () => {
    // 建一张已审核的订单（不依赖上一条用例，保持用例自洽）
    const so = await fx.createDraft('sales_order', {
      docDate: F.date,
      customerId: F.customer.id,
      shipWarehouseId: F.whA.id,
      items: [{ productId: F.product.id, qty: 2, unitPrice: 3 }],
    }, '建销售订单（下推出库用例）');
    await okData(api, await fx.act('sales_order', so.id, 'submit'), '提交销售订单');
    await okData(api, await fx.approve('sales_order', so.id), '审核销售订单');

    // 契约预期：与前端 PUSH_CHAINS.sales_order.push（pathSuffix '/push'，裸数组 [{srcItemId,qty}]）一致；
    // 参照采购线已落地的 `/erp/pur/order/{id}/push/stock-in?warehouseId=`
    const res = await api.post(`/sal/order/${so.id}/push`, [], { warehouseId: F.whA.id, remark: `${TAG} 下推出库` });
    if (res.body.msg && /未知异常|Not Found|404/i.test(String(res.body.msg))) {
      throw new Error(
        '销售订单→出库单下推端点尚未落地（t8 未完成）：' +
        `POST /sal/order/{id}/push → HTTP ${res.status} / msg=${res.body.msg}\n` +
        '  已知：前端 doc-rules.js 的 PUSH_CHAINS.sales_order.push 标着 pending:true，ErpSalesController 只有 sal:request:push。\n' +
        '  本用例按契约预期写死断言，t8 落地后应直接变绿；这条红是"未部署"，不是"用例写错"。'
      );
    }
    const data = await okData(api, res, '下推销售出库单');
    const soutId = data.stockOutId || (data.stockOut && data.stockOut.id) || data.id;
    expect(soutId, '下推应返回出库单').toBeTruthy();
    const sout = await fx.detail('stock_out', soutId);
    fx.trackDoc('stock_out', sout);
    expect(sout.status, '下推生成草稿出库单').toBe('draft');
    expect(sout.warehouseId, '应带过发货仓库').toBe(F.whA.id);
    expect(sout.sourceDocNo, '应记录来源订单号').toBe((await fx.detail('sales_order', so.id)).docNo);

    // 过账后：订单行 shippedQty 回写、结存减少
    await okData(api, await fx.act('stock_out', soutId, 'submit'), '提交下推的出库单');
    await okData(api, await fx.approve('stock_out', soutId), '审核过账');
    const soItems = await fx.items('sales_order', so.id);
    expect(Number(soItems[0].shippedQty), '过账后回写已出库量').toBeCloseTo(2, 3);
  });
});
