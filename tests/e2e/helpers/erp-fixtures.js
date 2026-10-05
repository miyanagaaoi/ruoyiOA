/**
 * B4 进销存 E2E 的**夹具与清理**（HTTP 层）。
 *
 * 三条铁律（写在这里，用例里不再各写一遍）：
 *   1. **夹具可识别**：物料/仓库/单号一律带 ASCII 前缀 `E2E4B-` / `E2E4B`，便于排查与清理；
 *   2. **能复用就不新建**：主数据（单位/类型/物料/仓库）按 `code` 幂等复用，避免每跑一次多一份脏档案；
 *   3. **收尾必须真的清**：单据按业务接口 作废/删除（不容许留下"活跃单据"），
 *      主数据在"账已红冲、结存归零"之后尝试物理删除；**被引用守卫拒绝的要如实打印**，
 *      并退化为"停用"，绝不假装清干净了。
 *
 * ⚠ 为什么主数据可能删不掉（预期内，不是 bug）：`t_ctms_stock` / `t_ctms_stock_ledger` 都外键指向
 *   物料与仓库，而**流水按设计只增不改**（`notes/07-ledger.md` §4）。守卫文案见
 *   `erp/master/ErpMasterRefGuards.java`（"物料已有库存结存/流水，无法删除"…）。
 *   所以"零残留"的判定是：**零活跃单据** + 结存归零 + 主数据尽量删除（否则停用并列出）。
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const { expect } = require('@playwright/test');
const { okData, okList, bizError } = require('./api');

/** 8 类单据的 REST base + 动作形态（真源 = 各 Controller 的 @RequestMapping/@XxxMapping） */
const KIND = {
  purchase_request: { base: '/erp/pur/request', style: 'put-id-action', label: '采购申请单' },
  purchase_order: { base: '/erp/pur/order', style: 'put-id-action', label: '采购单' },
  sales_request: { base: '/sal/request', style: 'put-id-action', label: '销售申请单' },
  sales_order: { base: '/sal/order', style: 'put-id-action', label: '销售订单' },
  stock_in: { base: '/stk/in-order', style: 'post-action-id', label: '入库单' },
  stock_out: { base: '/stk/out-order', style: 'post-action-id', label: '出库单' },
  stock_transfer: { base: '/stk/transfer', style: 'post-id-action', label: '调拨单' },
  stock_take: { base: '/stk/take', style: 'post-id-action', label: '盘点单' },
};

/** 动作 URL：入库/出库是 `POST {base}/{action}/{id}`，采购/销售/调拨/盘点是 `{base}/{id}/{action}` */
function actionUrl(kindCode, action, id) {
  const k = KIND[kindCode];
  if (k.style === 'post-action-id') return { method: 'post', path: `${k.base}/${action}/${id}` };
  return { method: k.style === 'put-id-action' ? 'put' : 'post', path: `${k.base}/${id}/${action}` };
}

function pad(n) {
  return String(n).padStart(2, '0');
}
/** yyyy-MM-dd（后端 docDate 用 `@DateTimeFormat`/Date 解析，格式按 notes 的示例） */
function today() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

class ErpFixtures {
  /**
   * @param {import('./api').Api} api
   * @param {string} [tag] ASCII 前缀（默认带时间戳，便于区分不同轮次）
   */
  constructor(api, tag) {
    this.api = api;
    this.tag = tag || `E2E4B${Date.now().toString(36).toUpperCase()}`;
    /** 建出来的单据：{ kind, id, docNo, status } */
    this.docs = [];
    /** 主数据：{ type, id, code, name } */
    this.masters = [];
    this.report = [];
  }

  /** 记录一条单据（后续清理与断言都要用） */
  trackDoc(kind, doc) {
    const entry = { kind, id: doc.id || doc.docId || doc, docNo: doc.docNo, status: doc.status };
    this.docs.push(entry);
    return entry;
  }

  /** 记录一条主数据 */
  trackMaster(type, row) {
    this.masters.push({ type, id: row.id, code: row.code, name: row.name });
    return row;
  }

  /* ============================ 主数据（幂等） ============================ */

  async ensureUom(code, name, decimals) {
    const hit = await this.findByCode('/ctms/uom/list', code);
    if (hit) return this.trackMaster('uom', hit);
    const data = await okData(this.api, await this.api.post('/ctms/uom', { code, name, decimals, enableFlag: '1' }), `建计量单位 ${code}`);
    const row = typeof data === 'object' && data && data.id ? data : await this.findByCode('/ctms/uom/list', code);
    expect(row && row.id, `计量单位 ${code} 建完后应能查到`).toBeTruthy();
    return this.trackMaster('uom', row);
  }

  async ensureProductType(code, name) {
    const hit = await this.findByCode('/ctms/product-type/list', code);
    if (hit) return this.trackMaster('productType', hit);
    await okData(this.api, await this.api.post('/ctms/product-type', { code, name, enableFlag: '1' }), `建商品类型 ${code}`);
    const row = await this.findByCode('/ctms/product-type/list', code);
    expect(row && row.id, `商品类型 ${code} 建完后应能查到`).toBeTruthy();
    return this.trackMaster('productType', row);
  }

  async ensureProduct(code, name, { productTypeId, uomId, spec = 'E2E规格', safetyStock = 0 }) {
    const hit = await this.findByCode('/ctms/product/list', code);
    if (hit) return this.trackMaster('product', hit);
    await okData(this.api, await this.api.post('/ctms/product', {
      code, name, spec, productTypeId, uomId, safetyStock, enableFlag: '1',
    }), `建物料 ${code}`);
    const row = await this.findByCode('/ctms/product/list', code);
    expect(row && row.id, `物料 ${code} 建完后应能查到`).toBeTruthy();
    return this.trackMaster('product', row);
  }

  async ensureWarehouse(code, name) {
    const hit = await this.findByCode('/ctms/warehouse/list', code);
    if (hit) return this.trackMaster('warehouse', hit);
    await okData(this.api, await this.api.post('/ctms/warehouse', { code, name, enableFlag: '1' }), `建仓库 ${code}`);
    const row = await this.findByCode('/ctms/warehouse/list', code);
    expect(row && row.id, `仓库 ${code} 建完后应能查到`).toBeTruthy();
    return this.trackMaster('warehouse', row);
  }

  /** 供应商（采购单必填 `supplierId`；端点 `/ctms/partner/supplier`，必填 code+name） */
  async ensureSupplier(code, name) {
    return this.ensurePartner('supplier', code, name);
  }

  /** 客户（销售订单必填 `customerId`；端点 `/ctms/partner/customer`） */
  async ensureCustomer(code, name) {
    return this.ensurePartner('customer', code, name);
  }

  async ensurePartner(type, code, name) {
    const listPath = `/ctms/partner/${type}/list`;
    const hit = await this.findByCode(listPath, code);
    if (hit) return this.trackMaster(type, hit);
    // ⚠ 供应商比客户严：**简称必填**（`CtmsPartnerServiceImpl` 第 212 行注释与校验）；
    //   客户只要求 编码+名称。这里统一带上 shortName，对客户是无害冗余。
    await okData(this.api, await this.api.post(`/ctms/partner/${type}`, {
      code, name, shortName: name, enableFlag: '1',
    }), `建${type} ${code}`);
    const row = await this.findByCode(listPath, code);
    expect(row && row.id, `${type} ${code} 建完后应能查到`).toBeTruthy();
    return this.trackMaster(type, row);
  }

  /** 按 code 精确找（列表是分页的 ⇒ 显式要一大页，再在 Java 侧精确比对，与后端 `selectByName` 同思路） */
  async findByCode(listPath, code) {
    const res = await this.api.get(listPath, { pageNum: 1, pageSize: 500 });
    const { rows } = await okList(this.api, res, `列表 ${listPath}`);
    return rows.find((r) => String(r.code || '').trim() === code) || null;
  }

  /**
   * 一次性备齐本套用例要用的主数据：
   *   · 单位 3 位小数 / 0 位小数各一个（精度与"最小单位"负例都要用）
   *   · 一个叶子商品类型 + 两个物料（挂在它下面）
   *   · 两个仓库（调拨要一进一出）
   */
  async provision() {
    const t = this.tag;
    const uom3 = await this.ensureUom(`${t}-U3`, `${t}计量单位3位`, 3);
    const uom0 = await this.ensureUom(`${t}-U0`, `${t}计量单位0位`, 0);
    const type = await this.ensureProductType(`${t}-T`, `${t}商品类型`);
    const product = await this.ensureProduct(`${t}-P3`, `${t}球阀`, { productTypeId: type.id, uomId: uom3.id });
    const product0 = await this.ensureProduct(`${t}-P0`, `${t}螺栓`, { productTypeId: type.id, uomId: uom0.id });
    const whA = await this.ensureWarehouse(`${t}-WA`, `${t}一号仓`);
    const whB = await this.ensureWarehouse(`${t}-WB`, `${t}二号仓`);
    const supplier = await this.ensureSupplier(`${t}-S`, `${t}供应商`);
    const customer = await this.ensureCustomer(`${t}-C`, `${t}客户`);
    return { uom3, uom0, type, product, product0, whA, whB, supplier, customer, date: today() };
  }

  /* ============================ 单据动作 ============================ */

  /**
   * 通用动作（提交/审核/驳回/反审核/置完成）。
   *
   * `reason` **同时**放进 query 与 body（实测：三条线的取法不同，见各 Controller 签名）：
   *   · 入库/出库 `POST {base}/{action}/{id}?reason=`（`@RequestParam`，不接受 body）；
   *   · 采购 `PUT {base}/{id}/{action}?reason=`（query）；
   *   · 销售 `PUT {base}/{id}/{action}` 的 `@RequestBody(required=false) Map`（**只看 body**，
   *     只给 query 会被判"原因必填" ⇒ 反审核/作废做不掉、单据变残留）；
   *   · 调拨/盘点两种都收。多给的那一半会被 Spring 忽略，不会报错。
   * `params` 用于 `action=approve|reject` 这类 query 口径。
   */
  async act(kindCode, id, action, { reason, data, params } = {}) {
    const a = actionUrl(kindCode, action, id);
    const query = Object.assign({}, params || {});
    if (reason) query.reason = reason;
    const q = Object.keys(query).length ? query : undefined;
    const payload = reason ? Object.assign({ reason }, data || {}) : data;
    const res = a.method === 'put'
      ? await this.api.put(a.path, payload, q)
      : await this.api.post(a.path, payload, q);
    return res;
  }

  /** 审核（采购/销售线是 `PUT {id}/approve?action=approve`；库存线是 `POST /approve/{id}`） */
  async approve(kindCode, id, { reason } = {}) {
    const k = KIND[kindCode];
    if (k.style === 'put-id-action') {
      return this.act(kindCode, id, 'approve', { params: { action: 'approve' }, reason });
    }
    return this.act(kindCode, id, 'approve', { reason });
  }

  /**
   * 建草稿：`POST {base}`（体由调用方给），并把单据记录下来（含 id/docNo/status）。
   *
   * 三种返回形态都必须兜住（**实测**，不是猜的）：
   *   ① `data` = 单据对象（含 `id`）—— 入库单/出库单/销售申请/销售订单/调拨/盘点；
   *   ② `data` = 主键字符串；
   *   ③ **`msg` = 主键、没有 `data`** —— 采购申请单/采购单（B3 的"插入回 id 放 msg"约定），
   *      实测 `POST /erp/pur/request` → `{"msg":"E97F2F75…","code":200}`。
   * 兜住之后再 `detail()` 回读一次：既验证 id 真的可用，也把 `docNo/status` 拿全
   * （后续断言与清理都要用），避免把 `[object Object]` 之类的东西写进 id。
   */
  async createDraft(kindCode, body, what) {
    const label = what || `建${KIND[kindCode].label}`;
    const res = await this.api.post(KIND[kindCode].base, body);
    expect(res.status, `[${label}] HTTP 状态（body=${JSON.stringify(res.body).slice(0, 200)}）`).toBe(200);
    expect(res.body.code, `[${label}] body.code（msg=${res.body.msg}）`).toBe(200);

    let id = null;
    const data = res.body.data;
    if (data && typeof data === 'object') {
      id = data.id || data.docId || null;
    } else if (typeof data === 'string' && data) {
      id = data;
    }
    // ③ msg 形态：32 位十六进制主键（B3 约定）；`操作成功` 这类文案不会被误判
    if (!id && /^[A-Za-z0-9]{32}$/.test(String(res.body.msg || ''))) {
      id = res.body.msg;
    }
    expect(id, `[${label}] 未能从响应里取到单据 id：body=${JSON.stringify(res.body).slice(0, 200)}`).toBeTruthy();

    // 回读一次：拿全 docNo/status，并证明该 id 真的能查（不把坏 id 带进清理阶段）
    const doc = await this.detail(kindCode, id);
    expect(doc && doc.id, `[${label}] 建单后应能按 id 查到详情`).toBeTruthy();
    return this.trackDoc(kindCode, doc);
  }

  /** 详情（拿到最新状态） */
  async detail(kindCode, id) {
    const res = await this.api.get(`${KIND[kindCode].base}/${id}`);
    return okData(this.api, res, `详情 ${kindCode}/${id}`);
  }

  /**
   * 行项。
   *
   * ⚠ 只有采购/销售 4 类有 `GET {base}/{id}/items`（grep 过：`ErpPurchaseRequest/OrderController`、
   * `ErpSalesController`）；**入库/出库/调拨/盘点没有**这个端点 ⇒ 用详情里内联的 `items`
   * （详情口径本身就是"含行项"，见 notes/06-stockops.md §7）。
   */
  async items(kindCode, id) {
    const res = await this.api.get(`${KIND[kindCode].base}/${id}/items`);
    if (res.body.code === 200 && Array.isArray(res.body.data)) {
      return res.body.data;
    }
    const doc = await this.detail(kindCode, id);
    expect(Array.isArray(doc.items), `行项 ${kindCode}/${id} 既无 items 端点也无内联 items`).toBe(true);
    return doc.items;
  }

  /* ============================ 清理 ============================ */

  /**
   * 收尾：单据先删（草稿）/作废（其余），再尝试删主数据，最后打印清理报告。
   * @returns {Promise<{activeDocs:number, deletedMasters:number, residualMasters:Array, refused:Array}>}
   */
  async cleanup() {
    const refused = [];
    let deleted = 0;
    let voided = 0;

    // 1) 单据：逆序（后建的先处理，天然满足"先清下游再清上游"）
    for (const d of this.docs.slice().reverse()) {
      const k = KIND[d.kind];
      try {
        let cur = await this.detail(d.kind, d.id).catch(() => null);
        if (!cur) { refused.push(`${k.label} ${d.docNo || d.id} 读不到（可能已被删除）`); continue; }

        if (cur.status === 'draft') {
          const del = await this.api.del(`${k.base}/${d.id}`);
          if (del.body.code === 200) { deleted++; this.report.push(`删除草稿 ${k.label} ${cur.docNo}`); continue; }
          refused.push(`删除草稿 ${k.label} ${cur.docNo}：${del.body.msg}`);
        }

        // **先反审核**（`approved` / `completed` 都必须先回到可作废的状态；实测：
        //   approved --unapprove--> submitted、completed --unapprove--> draft，之后 void 才 200；
        //   直接对 approved/completed 作废会被拒："当前状态（已审核/已完成）不可执行「作废」"）
        if (cur.status === 'approved' || cur.status === 'completed') {
          const un = await this.act(d.kind, d.id, 'unapprove', { reason: 'E2E4 用例清理' });
          cur = await this.detail(d.kind, d.id).catch(() => cur);
          if (cur.status === 'approved' || cur.status === 'completed') {
            refused.push(`反审核 ${k.label} ${cur.docNo} 未成功（仍 ${cur.status}）：${un.body.msg || ''}`);
          }
        }
        if (cur.status !== 'voided') {
          const vp = k.style === 'post-action-id' ? `${k.base}/void/${d.id}` : `${k.base}/${d.id}/void`;
          const vr = k.style === 'put-id-action'
            ? await this.api.put(vp, { reason: 'E2E4 用例清理' }, { reason: 'E2E4 用例清理' })
            : await this.api.post(vp, { reason: 'E2E4 用例清理' }, { reason: 'E2E4 用例清理' });
          cur = await this.detail(d.kind, d.id).catch(() => cur);
          if (cur && cur.status !== 'voided') {
            refused.push(`作废 ${k.label} ${cur.docNo} 未成功（仍 ${cur.status}）：${vr.body.msg || ''}`);
          }
        }
        if (cur && cur.status === 'voided') { voided++; this.report.push(`作废 ${k.label} ${cur.docNo}`); continue; }
      } catch (e) {
        refused.push(`清理 ${k.label} ${d.docNo || d.id} 异常：${e.message}`);
      }
    }

    // 1b) 兜底扫尾：按 tag 关键字把**没被 registry 记住**的残留也清一遍
    //     （典型来源：下推产生的下游单据——只有登记过才能被"后建先清"的倒序覆盖；
    //      漏登记时上游会被下游守卫挡住 ⇒ 双双留库。这一步用列表接口兜住这类遗漏。）
    for (const kindCode of Object.keys(KIND)) {
      const k = KIND[kindCode];
      let listed;
      try {
        listed = await this.api.get(`${k.base}/list`, { keyword: this.tag, pageNum: 1, pageSize: 100 });
      } catch (e) {
        continue;
      }
      if (listed.body.code !== 200 || !Array.isArray(listed.body.rows)) continue;
      for (const row of listed.body.rows) {
        try {
          if (row.status === 'draft') {
            const del = await this.api.del(`${k.base}/${row.id}`);
            if (del.body.code === 200) { deleted++; this.report.push(`删除草稿（兜底） ${k.label} ${row.docNo}`); continue; }
          }
          const vp = k.style === 'post-action-id' ? `${k.base}/void/${row.id}` : `${k.base}/${row.id}/void`;
          const send = (p) => (k.style === 'put-id-action'
            ? this.api.put(p, { reason: 'E2E4 用例清理' }, { reason: 'E2E4 用例清理' })
            : this.api.post(p, { reason: 'E2E4 用例清理' }, { reason: 'E2E4 用例清理' }));
          if (row.status === 'approved' || row.status === 'completed') {
            await this.act(kindCode, row.id, 'unapprove', { reason: 'E2E4 用例清理' });
          }
          await send(vp);
          const after = await this.detail(kindCode, row.id).catch(() => null);
          if (after && after.status === 'voided') { voided++; this.report.push(`作废（兜底） ${k.label} ${row.docNo}`); }
          else { refused.push(`兜底清理未成功 ${k.label} ${row.docNo}：仍 ${after && after.status}`); }
        } catch (e) {
          refused.push(`兜底清理 ${k.label} ${row.docNo} 异常：${e.message}`);
        }
      }
    }

    // 2) 主数据：物料 → 商品类型 / 单位 → 仓库（被引用守卫拒绝的如实记录并停用）
    const residualMasters = [];
    for (const m of this.masters.slice().reverse()) {
      const path = {
        product: '/ctms/product', productType: '/ctms/product-type', uom: '/ctms/uom',
        warehouse: '/ctms/warehouse', supplier: '/ctms/partner/supplier', customer: '/ctms/partner/customer',
      }[m.type];
      if (!path) continue;
      const res = await this.api.del(`${path}/${m.id}`);
      if (res.body.code === 200) { deleted++; this.report.push(`删除主数据 ${m.type} ${m.code}`); continue; }
      const msg = res.body.msg || `HTTP ${res.status}/code ${res.body.code}`;
      residualMasters.push({ ...m, reason: msg });
      // 退化为停用（避免被后续用例误选）
      await this.api.put(path, { id: m.id, enableFlag: '0' }).catch(() => {});
    }

    // 3) 复核"零活跃单据"：按关键字查列表（作废默认不返回）
    //    ⚠ 查不到的两种情形都必须说清楚，否则"复核没红"会被误读成"清干净了"：
    //      · 列表接口本身报错（例：`/sal/request/list` 因后端 ClassCastException 返回 code=500）⇒ 记为无法复核；
    //      · 关键字搜不到 —— 列表的 keyword 口径是 doc_no/备注/来源单号，**不含**仓库名/物料名，
    //        所以夹具靠 remark/用途 标记才能被搜到（本套用例建单都会带 tag 备注；带不了备注的按 id 复核）。
    const active = [];
    const unchecked = [];
    for (const kindCode of Object.keys(KIND)) {
      const k = KIND[kindCode];
      const res = await this.api.get(`${k.base}/list`, { keyword: this.tag, pageNum: 1, pageSize: 100 });
      if (res.body.code !== 200) {
        unchecked.push(`${k.label} 列表接口不可用（HTTP ${res.status}/code ${res.body.code}：${res.body.msg}）⇒ 无法用关键字复核残留`);
        continue;
      }
      if (Array.isArray(res.body.rows) && res.body.rows.length) {
        active.push(`${k.label} 仍有 ${res.body.rows.length} 行：${res.body.rows.map((r) => r.docNo).join(',')}`);
      }
    }

    const summary = {
      activeDocs: active.length,
      activeDetail: active,
      deleted,
      voided,
      residualMasters,
      refused,
      unchecked,
      report: this.report,
    };
    console.log(
      `\n[E2E4 清理报告] tag=${this.tag}\n` +
      `  删除：${deleted} 条；作废：${voided} 条\n` +
      `  仍活跃的单据：${active.length}${active.length ? '\n    ' + active.join('\n    ') : ''}\n` +
      `  未删除的主数据（引用守卫拒绝，已停用）：${residualMasters.length}` +
      (residualMasters.length ? '\n    ' + residualMasters.map((m) => `${m.type} ${m.code}：${m.reason}`).join('\n    ') : '') +
      (unchecked.length ? `\n  ⚠ 无法复核残留（接口不可用，非本用例造成）：\n    ${unchecked.join('\n    ')}` : '') +
      (refused.length ? `\n  其它未清理项：\n    ${refused.join('\n    ')}` : '') +
      '\n'
    );
    return summary;
  }

  /** 清理后必须为 0 的部分（用例里 `expectZeroResidue()` 直接用） */
  async expectZeroResidue(summary) {
    expect(summary.activeDocs, `清理后不应有活跃单据：${summary.activeDetail.join('；')}`).toBe(0);
  }
}

/**
 * 库存重算一致性：**按自己夹具的 (物料, 仓库) key 断言**，全局数字照实打印。
 *
 * 为什么不是"全局 `inconsistentCount == 0`"（队长 2026-10-06 裁决采纳）：
 *   `erp-check` / `erp-scope-check` 与 E2E 都在同一张表上建单改数，全局计数会被**别人在飞的夹具**
 *   绑架（实测见过 `PT26000813/WAT26000813：stock=15、ledger=5、diff=+10` 这类运行期派生差异），
 *   从而制造假红、掩盖真正的交付不变量。真正的不变量是"**我方夹具自洽** + 全局差异被如实上报以便分诊"。
 *
 * @param {import('./api').Api} api
 * @param {Array<{productId:string, warehouseId:string}>} keys 本用例夹具的 key
 */
async function recalcScoped(api, keys) {
  const data = await okData(api, await api.post('/stk/stock/recalc', null, { repair: 'false' }), '库存重算（只读）');
  const mismatches = data.mismatches || [];
  const mine = mismatches.filter((m) => keys.some((k) => k.productId === m.productId && k.warehouseId === m.warehouseId));
  const others = mismatches.filter((m) => !mine.includes(m));
  console.log(
    `[E2E4 recalc] 全局：checkedRows=${data.checkedRows} consistent=${data.consistent} ` +
    `inconsistentCount=${data.inconsistentCount} repair=${data.repair} repairedCount=${data.repairedCount}`
  );
  if (others.length) {
    console.log(
      `[E2E4 recalc] ⚠ 全局不一致里**不属于本用例**的 key（信息，不计本用例失败；多为其它门禁脚本在飞的夹具）：\n    ` +
      others.map((m) => `product=${m.productId} wh=${m.warehouseId} stock=${m.stockQty} ledger=${m.ledgerQty} diff=${m.diff}`).join('\n    ')
    );
  }
  expect(mine, `本用例夹具的 (物料, 仓库) 不应有不一致：${JSON.stringify(mine)}`).toHaveLength(0);
  return data;
}

module.exports = { ErpFixtures, KIND, actionUrl, today, bizError, recalcScoped };
