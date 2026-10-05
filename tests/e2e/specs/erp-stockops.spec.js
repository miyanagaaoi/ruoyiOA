/**
 * B4 调拨 / 盘点 · HTTP 层 E2E
 *
 *   调拨：审核后两仓结存双向变化、流水中单价为 0、**货品总额度不变**（调拨不算货值）
 *   盘点：生成行项 → 录入实盘 → 审核 → 自动生成盘盈/盘亏单并过账 → 反审核级联（结存回位）
 *
 * 端点真源：
 *   · 调拨 `ErpTransferController`（`/stk/transfer`，`POST /submit|approve|unapprove|void/{id}` 或 `/{id}/submit`…）
 *   · 盘点 `ErpStocktakeController`（`/stk/take`，另加 `POST /{id}/generate`、`PUT /{id}/count`）
 *   · 结存/流水 `/stk/stock/list`、`/stk/ledger/list`
 * 口径：notes/06-stockops.md §2/§3/§4/§5/§7/§8、notes/07-ledger.md §3（额度口径）
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const { test, expect } = require('@playwright/test');
const { createApi, okData, okList, bizError } = require('../helpers/api');
const { ErpFixtures } = require('../helpers/erp-fixtures');

const TAG = `E2E4K${Date.now().toString(36).toUpperCase()}`;

let api, dispose, fx, F;

async function stockRow(productId, warehouseId) {
  const { rows } = await okList(api, await api.get('/stk/stock/list', { productId, warehouseId, pageSize: 50 }), '库存明细');
  return rows.length ? rows[0] : null;
}
async function qtyOf(productId, warehouseId) {
  const row = await stockRow(productId, warehouseId);
  return row ? Number(row.qty) : 0;
}
async function ledgerOf(productId, warehouseId, docNo) {
  const { rows } = await okList(api, await api.get('/stk/ledger/list', { productId, warehouseId, pageSize: 200 }), '库存流水');
  return docNo ? rows.filter((r) => r.docNo === docNo) : rows;
}
/** 用一张采购入库单把结存垫到指定数量（本套用例自备货，不依赖别人的数据） */
async function seedStock(productId, warehouseId, qty, unitPrice = 1) {
  const sin = await fx.createDraft('stock_in', {
    docDate: F.date, warehouseId, inType: '采购入库',
    items: [{ productId, qty, unitPrice }],
  }, '备货入库单');
  await okData(api, await fx.act('stock_in', sin.id, 'submit'), '提交备货入库');
  await fx.approve('stock_in', sin.id);
  return sin;
}

test.describe('B4 调拨 / 盘点（HTTP 层）', () => {
  test.beforeAll(async () => {
    const created = await createApi('superAdmin');
    api = created.api;
    dispose = created.dispose;
    fx = new ErpFixtures(api, TAG);
    F = await fx.provision();
    console.log(`[E2E4K] 夹具就绪 tag=${TAG} 物料=${F.product.code}/${F.product0.code} 仓库=${F.whA.code}/${F.whB.code}`);
  });

  test.afterAll(async () => {
    if (fx) {
      const summary = await fx.cleanup();
      await fx.expectZeroResidue(summary);
    }
    if (dispose) await dispose();
  });

  test('调拨：审核两仓双向变化 + 2 条流水（单价 0）+ 额度不变 + 幂等 + 红冲', async () => {
    await seedStock(F.product.id, F.whA.id, 10, 2);
    const waBefore = await qtyOf(F.product.id, F.whA.id);
    const wbBefore = await qtyOf(F.product.id, F.whB.id);
    const quotaBefore = Number((await stockRow(F.product.id, F.whA.id)).goodsQuota);
    expect(waBefore, '调出仓备货后 ≥10').toBeGreaterThanOrEqual(10);

    // ---------- 建单：行项强制清空行级仓库、单价恒 0 ----------
    const tr = await fx.createDraft('stock_transfer', {
      docDate: F.date,
      fromWarehouseId: F.whA.id,
      toWarehouseId: F.whB.id,
      handlerName: `${TAG}经办`,
      items: [{ productId: F.product.id, qty: 4, unitPrice: 99, warehouseId: F.whB.id }],
    }, '建调拨单');
    const trDetail = await fx.detail('stock_transfer', tr.id);
    expect(trDetail.status).toBe('draft');
    expect(String(trDetail.docNo), '调拨单号 DB + yyyyMM + 6 位').toMatch(/^DB\d{12}$/);
    expect(String(trDetail.posted), '草稿未过账').toBe('0');
    const trItems = await fx.items('stock_transfer', tr.id);
    expect(trItems[0].warehouseId, '调拨行项不使用行级仓库（请求里塞了也清空）').toBeFalsy();
    expect(Number(trItems[0].unitPrice || 0), '调拨不产生金额：单价恒 0').toBe(0);
    expect(Number(trItems[0].amount || 0), '调拨不产生金额：金额恒 0').toBe(0);

    // ---------- 提交 → 审核（两阶段过账） ----------
    await okData(api, await fx.act('stock_transfer', tr.id, 'submit'), '提交调拨单');
    await fx.approve('stock_transfer', tr.id);
    const approved = await fx.detail('stock_transfer', tr.id);
    expect(approved.status).toBe('approved');
    expect(String(approved.posted), '审核即过账').toBe('1');
    expect(await qtyOf(F.product.id, F.whA.id), '调出仓 -4').toBeCloseTo(waBefore - 4, 3);
    expect(await qtyOf(F.product.id, F.whB.id), '调入仓 +4').toBeCloseTo(wbBefore + 4, 3);

    // ---------- 流水：同一单据号两条、一负一正、单价 0 ----------
    const rows = await ledgerOf(F.product.id, F.whA.id, approved.docNo);
    expect(rows.length, '调拨应产生两条流水（流出）').toBe(1);
    expect(Number(rows[0].qtyChange), '流出为负').toBeCloseTo(-4, 3);
    expect(rows[0].bizType, '流出业务类型').toBe('调拨出库');
    expect(Number(rows[0].unitPrice || 0), '调拨流水单价 0').toBe(0);
    const inRows = await ledgerOf(F.product.id, F.whB.id, approved.docNo);
    expect(inRows.length, '调入侧也有一条').toBe(1);
    expect(Number(inRows[0].qtyChange), '流入为正').toBeCloseTo(4, 3);
    expect(inRows[0].bizType, '流入业务类型').toBe('调拨入库');

    // ---------- 额度不变：额度只统计「入库」子串且排除「调拨入库」 ----------
    const quotaAfter = Number((await stockRow(F.product.id, F.whA.id)).goodsQuota);
    expect(quotaAfter, '调拨不算货值 ⇒ 调出仓额度不变').toBeCloseTo(quotaBefore, 2);
    const quotaIn = Number((await stockRow(F.product.id, F.whB.id)).goodsQuota);
    expect(quotaIn, '调拨入库不计入额度（排除项）').toBeCloseTo(0, 2);

    // ---------- 幂等：重复审核不产生第二条流水 ----------
    await fx.approve('stock_transfer', tr.id);
    expect((await ledgerOf(F.product.id, F.whA.id, approved.docNo)).length, '重复审核流水条数不变').toBe(1);
    expect(await qtyOf(F.product.id, F.whA.id), '重复审核结存不变').toBeCloseTo(waBefore - 4, 3);

    // ---------- 反审核红冲：追加相反数流水、结存回位 ----------
    await okData(api, await fx.act('stock_transfer', tr.id, 'unapprove', { reason: `${TAG} 调错` }), '反审核调拨单');
    const reversed = await fx.detail('stock_transfer', tr.id);
    expect(reversed.status).toBe('submitted');
    expect(String(reversed.posted)).toBe('0');
    expect(await qtyOf(F.product.id, F.whA.id), '红冲后调出仓回位').toBeCloseTo(waBefore, 3);
    expect(await qtyOf(F.product.id, F.whB.id), '红冲后调入仓回位').toBeCloseTo(wbBefore, 3);
    const redRows = await ledgerOf(F.product.id, F.whA.id, approved.docNo);
    expect(redRows.length, '红冲是追加（原流水保留 + 1 条相反数）').toBe(2);
    expect(redRows.some((r) => String(r.bizType) === '红冲-调拨出库'), '红冲业务类型带前缀').toBe(true);
  });

  test('调拨守卫：同仓被拒；调出仓不足时整单不写（第 1 行也不写）', async () => {
    const same = await api.post('/stk/transfer', {
      docDate: F.date, fromWarehouseId: F.whA.id, toWarehouseId: F.whA.id,
      items: [{ productId: F.product.id, qty: 1 }],
    });
    await bizError(api, same, '调出仓=调入仓', /不能相同/);

    // 备货 3 到 whB，然后一张单里两行（3 与 1003）⇒ 第二行不足，整单不写
    await seedStock(F.product.id, F.whB.id, 3, 1);
    const wb = await qtyOf(F.product.id, F.whB.id);
    expect(wb, '备货后 whB 有 3').toBeCloseTo(3, 3);
    const tr = await fx.createDraft('stock_transfer', {
      docDate: F.date, fromWarehouseId: F.whB.id, toWarehouseId: F.whA.id,
      items: [
        { productId: F.product.id, qty: 3 },              // 第 1 行：刚好够
        { productId: F.product.id, qty: 1003 },           // 第 2 行：远远不够
      ],
    }, '建超量调拨单');
    await okData(api, await fx.act('stock_transfer', tr.id, 'submit'), '提交超量调拨单');
    const bad = await fx.approve('stock_transfer', tr.id);
    await bizError(api, bad, '调出仓不足', /可用量/);
    expect((await fx.detail('stock_transfer', tr.id)).status, '被拒后仍为待审核').toBe('submitted');
    expect(await qtyOf(F.product.id, F.whB.id), '整单不写：第 1 行也没写').toBeCloseTo(wb, 3);
    expect((await ledgerOf(F.product.id, F.whB.id, (await fx.detail('stock_transfer', tr.id)).docNo)).length, '被拒后无本单流水').toBe(0);
  });

  test('盘点（全盘）：生成行项 → 录入实盘 → 审核生成盘盈单并过账 → 反审核级联回位', async () => {
    // 用 whB + P0（0 位小数）做隔离，避免与调拨用例互相影响
    await seedStock(F.product0.id, F.whB.id, 5, 3);
    const book = await qtyOf(F.product0.id, F.whB.id);
    expect(book, '备货后账面').toBeCloseTo(5, 3);

    // ---------- 建盘点单（自动生成行项） ----------
    const st = await fx.createDraft('stock_take', {
      docDate: F.date, warehouseId: F.whB.id, takeType: 'full',
    }, '建盘点单（全盘）');
    const stDetail = await fx.detail('stock_take', st.id);
    expect(stDetail.status).toBe('draft');
    expect(String(stDetail.docNo), '盘点单号 ST + yyyyMM + 6 位').toMatch(/^ST\d{12}$/);
    const items = await fx.items('stock_take', st.id);
    const mine = items.find((it) => it.productId === F.product0.id);
    expect(mine, '全盘行项应含该仓库有结存的物料').toBeTruthy();
    expect(Number(mine.bookQty), '账面数量 = 生成那一刻的结存').toBeCloseTo(book, 3);
    expect(Number(mine.actualQty), '实盘默认等于账面（避免未录入就被算成全亏）').toBeCloseTo(book, 3);
    expect(Number(mine.diffQty), '初始差异 0').toBeCloseTo(0, 3);

    // ---------- 录入实盘（多 2，且请求里塞 bookQty 应被忽略） ----------
    await okData(api, await api.put(`/stk/take/${st.id}/count`, {
      items: [{ productId: F.product0.id, actualQty: book + 2, bookQty: 999, diffReason: `${TAG} 漏记` }],
    }), '录入实盘');
    const afterCount = (await fx.items('stock_take', st.id)).find((it) => it.productId === F.product0.id);
    expect(Number(afterCount.bookQty), '账面只读：请求里的 bookQty 被忽略').toBeCloseTo(book, 3);
    expect(Number(afterCount.actualQty)).toBeCloseTo(book + 2, 3);
    expect(Number(afterCount.diffQty), '差异 = 实盘 − 账面').toBeCloseTo(2, 3);
    expect(String((await fx.detail('stock_take', st.id)).posted), '未审核不过账').toBe('0');

    // ---------- 审核：自动生成盘盈入库单并过账 ----------
    await okData(api, await fx.act('stock_take', st.id, 'submit'), '提交盘点单');
    await fx.approve('stock_take', st.id);
    const approved = await fx.detail('stock_take', st.id);
    expect(approved.status).toBe('approved');
    expect(String(approved.posted), '盘点自身 posted 表示"生成单已过账"').toBe('1');
    expect(approved.generatedInNo, '应回填生成的盘盈入库单号').toBeTruthy();
    expect(await qtyOf(F.product0.id, F.whB.id), '盘盈 2 已过账 ⇒ 结存 +2').toBeCloseTo(book + 2, 3);
    const gen = approved.generatedInId ? await fx.detail('stock_in', approved.generatedInId) : null;
    if (gen) {
      fx.trackDoc('stock_in', gen);
      expect(gen.status, '生成的盘盈单直接已审核').toBe('approved');
      expect(String(gen.posted), '生成单已过账').toBe('1');
      expect(gen.inType, '入库类型').toBe('盘盈入库');
      expect(Number(gen.totalAmount || 0), '盘盈单金额 0').toBe(0);
      expect(gen.sourceDocType, '来源类型').toBe('stock_take');
    } else {
      // 若详情未内联生成单对象，用列表按单号反查（不放宽断言）
      const { rows } = await okList(api, await api.get('/stk/in-order/list', { keyword: approved.generatedInNo, pageSize: 20 }), '盘盈单列表');
      expect(rows.length, '盘盈入库单应能在列表查到').toBe(1);
      expect(rows[0].inType).toBe('盘盈入库');
      fx.trackDoc('stock_in', rows[0]);
    }

    // ---------- 反审核级联：生成单被红冲并作废、结存回位 ----------
    await okData(api, await fx.act('stock_take', st.id, 'unapprove', { reason: `${TAG} 实盘录错` }), '反审核盘点单');
    const reverted = await fx.detail('stock_take', st.id);
    expect(reverted.status).toBe('submitted');
    expect(String(reverted.posted)).toBe('0');
    expect(await qtyOf(F.product0.id, F.whB.id), '级联红冲后结存回到账面').toBeCloseTo(book, 3);
    const redRows = await ledgerOf(F.product0.id, F.whB.id);
    expect(redRows.some((r) => String(r.bizType).startsWith('红冲-')), '应有红冲流水').toBe(true);

    // ---------- 重复反审核：幂等不报错 ----------
    await okData(api, await fx.act('stock_take', st.id, 'unapprove', { reason: `${TAG} 重复反审核` }), '重复反审核（幂等）');
    expect(await qtyOf(F.product0.id, F.whB.id), '重复反审核不改结存').toBeCloseTo(book, 3);
  });

  test('盘点守卫：抽盘缺参被拒；实盘负数被拒', async () => {
    const noScope = await api.post('/stk/take', { docDate: F.date, warehouseId: F.whB.id, takeType: 'partial' });
    await bizError(api, noScope, '抽盘缺参', /抽盘必须指定物料或商品类型/);

    // 全盘盘点要求"该仓库有结存行"才生成得出来 ⇒ 先备货（否则合法入参也会被拒：
    // `盘点范围内没有可盘点的物料`，那是**产品正确行为**，不是这条守卫用例要测的东西）
    await seedStock(F.product0.id, F.whB.id, 1, 1);

    const st = await fx.createDraft('stock_take', {
      docDate: F.date, warehouseId: F.whB.id, takeType: 'full',
    }, '建盘点单（负数用例）');
    const bad = await api.put(`/stk/take/${st.id}/count`, {
      items: [{ productId: F.product0.id, actualQty: -1 }],
    });
    await bizError(api, bad, '实盘负数', /实盘数量不得为负数/);
  });

  test('盘亏豁免负库存：账面归零 → 实盘 0 → 过账到负结存（参数仍为 false）', async () => {
    // 1) 备货 2（P0/whB 是 0 位小数单位，数量用整数）——**账面取当前实值**：
    //    同文件前面的用例可能已往 whB 备过货，写死 2 会变成脆弱断言（本用例只关心"归零后再盘亏"）
    await seedStock(F.product0.id, F.whB.id, 2, 1);
    const book = await qtyOf(F.product0.id, F.whB.id);
    expect(book, '备货后账面应 > 0').toBeGreaterThan(0);

    // 2) 先建盘点单（账面在此刻固化为 book）
    const st = await fx.createDraft('stock_take', {
      docDate: F.date, warehouseId: F.whB.id, takeType: 'full',
    }, '建盘点单（盘亏豁免）');
    const mine = (await fx.items('stock_take', st.id)).find((it) => it.productId === F.product0.id);
    expect(Number(mine.bookQty), '账面已固化为当前结存').toBeCloseTo(book, 3);

    // 3) 把结存用一张出库单清零（模拟"东西已经没了"）
    const sout = await fx.createDraft('stock_out', {
      docDate: F.date, warehouseId: F.whB.id, outType: '其他出库',
      items: [{ productId: F.product0.id, qty: book, unitPrice: 1 }],
    }, '清零出库单');
    await okData(api, await fx.act('stock_out', sout.id, 'submit'), '提交清零出库单');
    await fx.approve('stock_out', sout.id);
    expect(await qtyOf(F.product0.id, F.whB.id), '结存已清零').toBeCloseTo(0, 3);

    // 4) 实盘 0 ⇒ 盘亏 book；过账时**显式豁免**负库存（参数 stock_allow_negative 仍是 false）
    await okData(api, await api.put(`/stk/take/${st.id}/count`, {
      items: [{ productId: F.product0.id, actualQty: 0, diffReason: `${TAG} 实物已耗用` }],
    }), '录入实盘 0');
    await okData(api, await fx.act('stock_take', st.id, 'submit'), '提交盘点单');
    await fx.approve('stock_take', st.id);
    const approved = await fx.detail('stock_take', st.id);
    expect(approved.status).toBe('approved');
    expect(approved.generatedOutNo, '应生成盘亏出库单').toBeTruthy();
    expect(await qtyOf(F.product0.id, F.whB.id), '盘亏豁免：结存允许转负').toBeCloseTo(-book, 3);

    const { rows } = await okList(api, await api.get('/stk/out-order/list', { keyword: approved.generatedOutNo, pageSize: 20 }), '盘亏单列表');
    expect(rows.length, '盘亏出库单应可在列表查到').toBe(1);
    expect(rows[0].outType, '出库类型').toBe('盘亏出库');
    fx.trackDoc('stock_out', rows[0]);
  });
});
