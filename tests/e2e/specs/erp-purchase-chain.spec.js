/**
 * B4 采购主链路 · HTTP 层 E2E（不依赖页面，可独立跑）
 *
 *   建物料/仓库/供应商夹具 → 采购申请（多行 + 小数单价）→ 提交 → 审核
 *     → 下推采购单 → 审核 → 下推入库单 → 审核过账
 *     → 断言库存明细出现「商品类型-物料名称」行与结存数量、库存流水出现对应入库记录
 *
 * 三层断言（本文件覆盖后两层，UI 层见 erp-ui-skeleton.spec.js）：
 *   ① 接口终态：单据状态 / posted / 行项已入库量（`receivedQty`）；
 *   ② 库存不变式：`GET /stk/stock/list` 的结存 == `GET /stk/ledger/list` 的数量变动累计，
 *      且 `POST /stk/stock/recalc` 在**本用例夹具的 (物料,仓库) key 上**无 mismatch（该比较**在 SQL 里**算，
 *      等价 DB 终态）；全局 `inconsistentCount/mismatches` 照实打印但**不**计本用例失败
 *      —— 别的门禁脚本（erp-check 等）会在同一张表上建单改数，全局计数会被在飞的夹具绑架（队长裁决）；
 *   ③ 关键口径：金额**先逐行舍入再汇总**（3×1×0.125 → 0.39，不是 0.38）、货品总额度只认「入库」子串
 *      且排除「调拨入库」（本用例用采购入库 + 红冲验证抵扣）。
 *   ⚠ 全程不碰 `ACT_*`（不走流程引擎）：本套用例只打 `/erp/**`、`/sal/**`、`/stk/**`、`/ctms/**`。
 *
 * 端点真源（逐个 grep，不凭猜）：
 *   · 采购申请 `ErpPurchaseRequestController` ：`POST /erp/pur/request`、`PUT {id}/submit|approve|unapprove|void`、
 *     `POST {id}/push/purchase-order?supplierId=&remark=`（body 可空 `[{srcItemId,qty}]`）
 *   · 采购单   `ErpPurchaseOrderController`   ：`PUT {id}/submit|approve|unapprove|void`、
 *     `POST {id}/push/stock-in?warehouseId=&remark=`
 *   · 入库单   `ErpStockInController`         ：`POST /stk/in-order`、`POST /submit/{id}`、`POST /approve/{id}`、
 *     `POST /unapprove/{id}?reason=`
 *   · 结存/流水/重算 `ErpStockBalanceController`/`ErpStockLedgerController`：`GET /stk/stock/list`、
 *     `GET /stk/ledger/list`、`POST /stk/stock/recalc?repair=false`
 * 口径与断言清单：notes/04a-procurement.md §5.1、notes/04b-procurement-push-in.md §8、
 *   notes/03-posting.md §8、notes/07-ledger.md §7
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const { test, expect } = require('@playwright/test');
const { createApi, okData, okList, bizError } = require('../helpers/api');
const { ErpFixtures, recalcScoped } = require('../helpers/erp-fixtures');

const TAG = `E2E4B${Date.now().toString(36).toUpperCase()}`;
/** 单号形状：两位大写前缀 + yyyyMM + 6 位序号（notes 04a §3 / 03-posting §8.1） */
const DOC_NO_RE = /^[A-Z]{2}\d{12}$/;

let api, dispose, fx, F;

test.describe('B4 采购主链路（HTTP 层）', () => {
  test.beforeAll(async () => {
    const created = await createApi('superAdmin');
    api = created.api;
    dispose = created.dispose;
    fx = new ErpFixtures(api, TAG);
    F = await fx.provision();
    console.log(`[E2E4B] 夹具就绪 tag=${TAG} 物料=${F.product.code}/${F.product0.code} 仓库=${F.whA.code}/${F.whB.code} 供应商=${F.supplier.code}`);
  });

  test.afterAll(async () => {
    if (fx) {
      const summary = await fx.cleanup();
      await fx.expectZeroResidue(summary);
    }
    if (dispose) await dispose();
  });

  test('申请 → 提交 → 审核 → 下推采购单 → 审核 → 下推入库单 → 过账 → 结存/流水/额度一致', async () => {
    const stockOf = async () => {
      const { rows } = await okList(api, await api.get('/stk/stock/list', { productId: F.product.id, warehouseId: F.whA.id, pageSize: 50 }), '库存明细');
      return rows.length ? Number(rows[0].qty) : 0;
    };
    const ledgerOf = async () => {
      const { rows } = await okList(api, await api.get('/stk/ledger/list', { productId: F.product.id, warehouseId: F.whA.id, pageSize: 100 }), '库存流水');
      return rows;
    };

    // ---------------- 0. 新物料/新仓库 ⇒ 结存为 0（干净起点，断言才有意义） ----------------
    expect(await stockOf(), '新夹具物料的初始结存应为 0').toBe(0);

    // ---------------- 1. 采购申请：多行 + 小数单价 ----------------
    // 行1：3 × 1.5 = 4.50；行2：2 × 0.125 = 0.25 ⇒ 单据金额 4.75（逐行舍入后汇总）
    const pr = await fx.createDraft('purchase_request', {
      docDate: F.date,
      purpose: `${TAG} 阀门采购`,
      items: [
        { productId: F.product.id, qty: 3, unitPrice: 1.5 },
        { productId: F.product0.id, qty: 2, unitPrice: 0.125 },
      ],
    }, '建采购申请单');
    const prDetail = await fx.detail('purchase_request', pr.id);
    expect(prDetail.status, '新建应为草稿').toBe('draft');
    expect(String(prDetail.docNo), '单号形状').toMatch(DOC_NO_RE);
    expect(Number(prDetail.totalAmount), '单据金额 = 各行舍入后之和').toBeCloseTo(4.75, 2);
    const prItems = await fx.items('purchase_request', pr.id);
    expect(prItems.length, '应有 2 行行项').toBe(2);
    expect(Number(prItems[0].remainingQty), '首行剩余可下推 = 数量').toBeCloseTo(3, 3);
    console.log(`[E2E4B] 采购申请 ${prDetail.docNo} 金额 ${prDetail.totalAmount}`);

    // ---------------- 2. 提交 → 审核 ----------------
    await okData(api, await fx.act('purchase_request', pr.id, 'submit'), '提交采购申请');
    expect((await fx.detail('purchase_request', pr.id)).status).toBe('submitted');
    await okData(api, await fx.approve('purchase_request', pr.id), '审核采购申请');
    expect((await fx.detail('purchase_request', pr.id)).status).toBe('approved');

    // ---------------- 3. 下推采购单（全量：不传 lines 即按剩余量全推） ----------------
    const pushRes = await api.post(`/erp/pur/request/${pr.id}/push/purchase-order`, null, {
      supplierId: F.supplier.id, remark: `${TAG} 下推`,
    });
    const pushData = await okData(api, pushRes, '下推采购单');
    expect(pushData.sourceDocNo, '下推结果应回来源单号').toBe(prDetail.docNo);
    expect(pushData.autoCompleted, '全部行项推完 ⇒ 申请单自动完成').toBe(true);
    const po = fx.trackDoc('purchase_order', pushData.order);
    expect(po.id, '下推应返回草稿采购单').toBeTruthy();
    expect(pushData.order.status, '下推生成的是草稿').toBe('draft');
    expect(pushData.order.sourceDocType || pushData.order.sourceDocId, '采购单应记录来源').toBeTruthy();
    expect((await fx.detail('purchase_request', pr.id)).status, '归零即完成').toBe('completed');

    // ---------------- 4. 采购单 审核 → 下推入库单 ----------------
    await okData(api, await fx.act('purchase_order', po.id, 'submit'), '提交采购单');
    await okData(api, await fx.approve('purchase_order', po.id), '审核采购单');
    expect((await fx.detail('purchase_order', po.id)).status).toBe('approved');

    const inPush = await api.post(`/erp/pur/order/${po.id}/push/stock-in`, null, {
      warehouseId: F.whA.id, remark: `${TAG} 下推入库`,
    });
    const inPushData = await okData(api, inPush, '下推入库单');
    const sin = fx.trackDoc('stock_in', inPushData.stockIn);
    expect(sin.id, '下推应返回草稿入库单').toBeTruthy();
    expect(inPushData.stockIn.warehouseId, '入库单收货仓库').toBe(F.whA.id);
    expect(inPushData.stockIn.status, '下推生成的是草稿').toBe('draft');
    // 未过账 ⇒ 不得回写已入库量（notes 04b §3 断言①）
    const poItemsBefore = await fx.items('purchase_order', po.id);
    expect(Number(poItemsBefore[0].receivedQty || 0), '未过账不得回写已入库量').toBe(0);

    // ---------------- 5. 入库单 提交 → 审核（审核即过账） ----------------
    await okData(api, await fx.act('stock_in', sin.id, 'submit'), '提交入库单');
    expect((await fx.detail('stock_in', sin.id)).status).toBe('submitted');
    await okData(api, await fx.approve('stock_in', sin.id), '审核入库单（过账）');
    const sinDetail = await fx.detail('stock_in', sin.id);
    expect(sinDetail.status, '审核后状态').toBe('approved');
    expect(String(sinDetail.posted), '审核即过账').toBe('1');

    // ---------------- 6. 库存明细：出现「商品类型-物料名称」行 + 结存数量 ----------------
    const { rows: stockRows } = await okList(api, await api.get('/stk/stock/list', {
      productId: F.product.id, warehouseId: F.whA.id, pageSize: 50,
    }), '库存明细');
    expect(stockRows.length, '应有 1 行结存').toBe(1);
    const stockRow = stockRows[0];
    expect(String(stockRow.displayName), '明细行展示名应为「商品类型-物料名称」').toBe(`${F.type.name}-${F.product.name}`);
    expect(Number(stockRow.qty), '结存数量 = 本次入库 3').toBeCloseTo(3, 3);
    expect(stockRow.goodsQuota, '应带货品总额度列').toBeDefined();
    // 额度 = Σ round(qty_change × unit_price, 2)：3 × 1.5 = 4.50（只统计「入库」子集）
    expect(Number(stockRow.goodsQuota), '货品总额度 = 入库行 3×1.5').toBeCloseTo(4.5, 2);

    // ---------------- 7. 库存流水：恰有对应入库记录 ----------------
    const ledger = await ledgerOf();
    expect(ledger.length, '应有 1 条流水').toBe(1);
    expect(ledger[0].bizType, '流水业务类型取入库类型').toBe('采购入库');
    expect(ledger[0].docType, '流水单据类型').toBe('stock_in');
    expect(String(ledger[0].docNo), '流水应带单据号').toBe(sinDetail.docNo);
    expect(Number(ledger[0].qtyChange), '数量变动 +3').toBeCloseTo(3, 3);
    expect(Number(ledger[0].qtyAfter), '变动后结存快照 = 3').toBeCloseTo(3, 3);
    expect(Number(ledger[0].unitPrice), '流水记录行单价').toBeCloseTo(1.5, 4);

    // ---------------- 8. 已入库量回写（过账时累加） ----------------
    const poItemsAfter = await fx.items('purchase_order', po.id);
    expect(Number(poItemsAfter[0].receivedQty), '过账后回写已入库量').toBeCloseTo(3, 3);

    // ---------------- 9. 不变式：结存 == Σ流水；重算按本夹具 key 一致（全局差异照实打印） ----------------
    const ledgerSum = ledger.reduce((s, r) => s + Number(r.qtyChange), 0);
    expect(await stockOf(), '结存应等于流水累计').toBeCloseTo(ledgerSum, 3);
    const recalc = await recalcScoped(api, [{ productId: F.product.id, warehouseId: F.whA.id }]);
    expect(recalc.repair, '默认只读（repair=false）').toBe(false);

    // ---------------- 10. 反审核红冲：结存回来、额度抵扣、已入库量回退 ----------------
    await okData(api, await fx.act('stock_in', sin.id, 'unapprove', { reason: `${TAG} 红冲验证` }), '反审核入库单');
    const afterReverse = await fx.detail('stock_in', sin.id);
    expect(afterReverse.status, '反审核后回到待审核').toBe('submitted');
    expect(String(afterReverse.posted), '过账标记清 0').toBe('0');
    expect(await stockOf(), '红冲后结存回 0').toBe(0);
    const ledger2 = await ledgerOf();
    expect(ledger2.length, '红冲是"追加相反数"，原流水保留').toBe(2);
    expect(ledger2.some((r) => String(r.bizType).startsWith('红冲-')), '应有红冲流水').toBe(true);
    const { rows: stockRows2 } = await okList(api, await api.get('/stk/stock/list', {
      productId: F.product.id, warehouseId: F.whA.id, pageSize: 50,
    }), '库存明细（红冲后）');
    expect(Number(stockRows2[0].goodsQuota), '红冲自动抵扣 ⇒ 额度回到 0.00').toBeCloseTo(0, 2);
    const poItemsReversed = await fx.items('purchase_order', po.id);
    expect(Number(poItemsReversed[0].receivedQty || 0), '红冲回退已入库量且不为负').toBe(0);

    // 收尾前把两张单据恢复成可作废的形态（本用例自身不依赖，交给 afterAll 统一清理）
    console.log('[E2E4B] 采购链路完成（含红冲与不变式校验）');
  });

  test('下推超量被拒：不改已下推量、不生成采购单（422 文案带剩余/本次）', async () => {
    const pr = await fx.createDraft('purchase_request', {
      docDate: F.date,
      purpose: `${TAG} 超量下推`,
      items: [{ productId: F.product.id, qty: 100, unitPrice: 1 }],
    }, '建采购申请单（超量用例）');
    await okData(api, await fx.act('purchase_request', pr.id, 'submit'), '提交');
    await okData(api, await fx.approve('purchase_request', pr.id), '审核');
    // 先推 60（合法）：**必须显式给数量** —— 传 `[]`/不传等于"按剩余量全推"（实测推到 100）
    const first = await okData(api, await api.post(`/erp/pur/request/${pr.id}/push/purchase-order`, [{ srcItemId: null, qty: 60 }], {
      supplierId: F.supplier.id,
    }), '首次下推 60');
    expect(Number(first.pushedQty), '首次下推量').toBeCloseTo(60, 3);
    // ⚠ 下推产生的采购单也要登记：清理由"后建先清"倒序做，漏登记会让上游申请单被下游守卫挡住、
    //    从而在收尾时变成活跃残留（实测踩过：PO 未 track ⇒ PR 反审核被拒 ⇒ 两张都留库）
    fx.trackDoc('purchase_order', first.order);
    // 再推 50（超量：剩余 40）
    const bad = await api.post(`/erp/pur/request/${pr.id}/push/purchase-order`, [{ srcItemId: null, qty: 50 }], {
      supplierId: F.supplier.id,
    });
    await bizError(api, bad, '超量下推', /剩余 40|可下推数量不足/);
    const itemsAfter = await fx.items('purchase_request', pr.id);
    expect(Number(itemsAfter[0].orderedQty), '被拒后已下推量不变').toBeCloseTo(60, 3);
  });

  test('金额口径：先逐行舍入再汇总（3 行 × 1 × 0.125 ⇒ 0.39，不是 0.38）', async () => {
    const pr = await fx.createDraft('purchase_request', {
      docDate: F.date,
      purpose: `${TAG} 舍入口径`,
      items: [
        { productId: F.product.id, qty: 1, unitPrice: 0.125 },
        { productId: F.product.id, qty: 1, unitPrice: 0.125 },
        { productId: F.product.id, qty: 1, unitPrice: 0.125 },
      ],
    }, '建采购申请单（舍入口径）');
    const items = await fx.items('purchase_request', pr.id);
    items.forEach((it, i) => expect(Number(it.amount), `第 ${i + 1} 行金额应逐行舍入到分`).toBeCloseTo(0.13, 2));
    const detail = await fx.detail('purchase_request', pr.id);
    expect(Number(detail.totalAmount), '汇总 = 0.13×3（先求和再舍入会得到 0.38）').toBeCloseTo(0.39, 2);
  });

  test('守卫：无行项不可提交 / 数量为 0 被拒 / 单位小数位超限被拒', async () => {
    // 空行项：**建单阶段就被拒**（实测 `POST /erp/pur/request {items:[]}` → code=500
    // 「单据至少需要一行行项」）——这比"先建草稿再提交失败"更靠前，断言按实际收紧
    const emptyCreate = await api.post('/erp/pur/request', { docDate: F.date, items: [] });
    await bizError(api, emptyCreate, '空行项建单', /没有行项|至少需要一行/);

    const zero = await api.post('/erp/pur/request', {
      docDate: F.date,
      items: [{ productId: F.product.id, qty: 0, unitPrice: 1 }],
    });
    await bizError(api, zero, '数量为 0', /数量必须大于 ?0/);

    // 0 位小数单位的物料给 1.5 ⇒ 精度守卫（文案「行 1：数量的最小单位是 0 位小数」）
    const badScale = await api.post('/erp/pur/request', {
      docDate: F.date,
      items: [{ productId: F.product0.id, qty: 1.5, unitPrice: 1 }],
    });
    await bizError(api, badScale, '精度超限', /0 位小数/);

    // 空行项草稿确实没落库（"先校验后写入"）
    const listed = await okList(api, await api.get('/erp/pur/request/list', { keyword: `${TAG} 空行项`, pageSize: 20 }), '空行项复核列表');
    expect(listed.rows.length, '被拒的空行项单据不应落库').toBe(0);
  });

  test('列表默认不含已作废；includeVoided=1 能查到（作废语义）', async () => {
    const pr = await fx.createDraft('purchase_request', {
      docDate: F.date,
      purpose: `${TAG} 作废语义`,
      items: [{ productId: F.product.id, qty: 1, unitPrice: 1 }],
    }, '建采购申请单（作废语义）');
    await okData(api, await fx.act('purchase_request', pr.id, 'void', { reason: 'E2E4B 作废语义' }), '作废采购申请');
    expect((await fx.detail('purchase_request', pr.id)).status).toBe('voided');
    const def = await okList(api, await api.get('/erp/pur/request/list', { keyword: pr.docNo, pageSize: 20 }), '默认列表');
    expect(def.rows.length, '默认列表不应含已作废').toBe(0);
    const withVoided = await okList(api, await api.get('/erp/pur/request/list', { keyword: pr.docNo, includeVoided: 1, pageSize: 20 }), 'includeVoided 列表');
    expect(withVoided.rows.length, 'includeVoided=1 应能查到').toBe(1);
  });
});
