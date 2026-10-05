import { listEnabledProducts, listEnabledWarehouses, listEnabledUoms } from '@/api/erp/masterdata'
import { listCustomerOptions, listSupplierOptions } from '@/api/ctms/partner'
import { listContract } from '@/api/ctms/contract'
import { rowsOf, dataOf } from './erp-response'
import { statusOptionsOf } from './doc-select-shape'
import rules from './doc-rules'

/**
 * 单据页面用到的**下拉选项加载**（列表筛选与表单共用一份）。
 *
 * 为什么单独一个模块：
 *   8 类单据页面都需要"物料 / 仓库 / 供应商 / 客户 / 合同"下拉，其中合同还要按方向
 *   （采购 PUR / 销售 SAL）过滤。若各页面自己拉一遍，就会出现 8 份重复的取数代码与
 *   8 种不统一的失败处理。这里按**单据配置里声明用到的字段**按需加载（用不到的不发请求）。
 *
 * 口径：
 *   · 物料 / 仓库 / 单位 → 走 `enableFlag='1'` 的列表查询（"停用后不出现在新单据下拉"）；
 *   · 供应商 / 客户 → 走 B3 已有的 `/options`（**只返回启用档案**）；
 *   · 合同 → 走 B3 的 `/ctms/contract/list` 并按 `type` 过滤方向（PUR/SAL），
 *     列表默认已排除已停用合同（`includeDeleted` 不传即为"只看未停用"）。
 *
 * ⚠ **取数层级必须逐点声明**（P-② 教训，2026-10-06）：列表接口体是 `{rows,total}`、
 *   `/options` 与树接口体是 `{data:[…]}`；一律读 `res.data` 会让列表类下拉**恒空**
 *   （8 类单据表单的仓库/物料/单位/合同全废）。故这里显式用 `rowsOf` / `dataOf`
 *   （实现与说明见 `views/erp/erp-response.js`），并由用例钉住每个装载点。
 *
 * ⚠ 单个下拉失败**不能**把整页拖垮：失败的项收集进 `failed`（页面显示一条提示、其余照用），
 *   既不是静默吞掉（`tools/audit` 的静默 catch 门禁），也不会让页面空白。
 */

/** 空选项集（失败或未加载时的安全默认值）。 */
export function emptyOptions() {
  return {
    products: [],
    warehouses: [],
    uoms: [],
    suppliers: [],
    customers: [],
    purchaseContracts: [],
    saleContracts: [],
    // 状态筛选的选项**前端自带真源**（`doc-rules.STATUSSES`），不发请求；
    // 以前这里没有这个键 ⇒ 8 类单据的「状态」筛选下拉恒空（P-② 同源）。
    docStatuses: statusOptionsOf(rules.STATUSSES),
    failed: []
  }
}

/** 该单据配置声明要用到哪些下拉。
 *
 * ⚠ 实现已抽到 `./doc-options-need`（CJS 纯函数，可被单测直接调用），并从本模块**原样转出口**。
 *   抽出的原因：这是"要不要发请求"的唯一判据，必须能被单测调用（而不是只读源码文本）；
 *   历史上它出过 P-⑤（联动判定早于扫描 ⇒ 单位列表从未请求），门禁需要能真正执行它。
 *   契约不变：入参 `kind`，返回 7 个布尔位。
 */
import { neededOptions } from './doc-options-need'

export { neededOptions }

/**
 * 按需加载下拉选项。
 *
 * @param {object} kind 单据类型配置（doc-kinds 的一项）
 * @returns {Promise<object>} 选项集合（含 `failed: string[]` 失败项说明）
 */
export function loadOptions(kind) {
  const need = neededOptions(kind)
  const options = emptyOptions()
  const jobs = []

  const collect = (key, label, request, pick) => {
    if (!need[key]) return
    jobs.push(
      request().then(
        (res) => {
          // `pick` 显式声明取数层级（rowsOf / dataOf）；见文件头 P-② 说明
          options[key] = pick(res)
        },
        (err) => {
          options.failed.push(label + '：' + describe(err))
        }
      )
    )
  }

  // 列表类接口（体是 {rows,total}）—— 5 处
  collect('products', '物料', () => listEnabledProducts(), rowsOf)
  collect('warehouses', '仓库', () => listEnabledWarehouses(), rowsOf)
  collect('uoms', '计量单位', () => listEnabledUoms(), rowsOf)
  collect('purchaseContracts', '采购方向合同', () => listContract({ type: 'PUR', pageNum: 1, pageSize: 500 }), rowsOf)
  collect('saleContracts', '销售方向合同', () => listContract({ type: 'SAL', pageNum: 1, pageSize: 500 }), rowsOf)
  // `/options` 类接口（体是 {data:[…]}）—— 2 处
  collect('suppliers', '供应商', () => listSupplierOptions(), dataOf)
  collect('customers', '客户', () => listCustomerOptions(), dataOf)

  if (!jobs.length) {
    return Promise.resolve(options)
  }
  return Promise.all(jobs).then(() => options)
}

function describe(err) {
  if (!err) return '加载失败'
  if (err.message) return err.message
  return String(err)
}
