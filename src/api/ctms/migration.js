import request from '@/utils/request'

// ============================================================================
// 历史甲乙方迁移（扫描 / 草案列表 / 认领 / 忽略）—— 2.0 B3 第 7 组 / 任务 8.5
// 后端：ruoyi-ctms 的 CtmsMigrationController（@RequestMapping("/ctms/migration")）
// 权限点真源：`sql/二开-合同台账-菜单.sql`（**未新增**权限点）
//   草案列表 = `ctms:migration:list`（页面菜单权限点）
//   扫描 = `ctms:migration:scan`｜认领 = `ctms:migration:claim`｜忽略 = `ctms:migration:ignore`
//
// 口径（真源 `notes/migration-notes.md` §1/§2，改前先读它）：
//   · 扫描只看「类型映射出来的那一侧」：PUR 看乙方 → 供应商方向、SAL 看甲方 → 客户方向，
//     其余类型一律跳过（不猜方向）；只收「该方向档案引用为空 + 该方向文本非空」的合同，
//     **包含已停用合同**；按「方向 + 原始文本」聚合（大小写/重音不敏感）。
//   · 重复扫描幂等：pending 只在计数变化时刷新、**ignored 整行不动（不复活）**、
//     claimed 若又出现新的未绑定合同会回到 pending 并清空 matchedId。
//   · 认领两条路径：带 `partyId` = 绑定已有档案（不新建）；不带 = 新建档案后绑定
//     （`code` **必填**，缺了报「新建档案必须提供编码」；`name` 缺省用草案 rawName；
//      供应商 `shortName` 缺省也用草案 rawName）。
//   · 认领按原始名称**批量回填**未绑定合同的档案引用（不改写文本、不覆盖已绑定别的档案的合同），
//     每份合同一条「客户档案/供应商档案」变更历史（source=auto），草案落 claimed。
//   · 认领/忽略的状态守卫：claimed 再认领或忽略都会被拒（文案含「已认领」）；
//     ignored 可重新认领、可重复忽略（幂等）。
// ============================================================================

/**
 * 扫描历史合同的甲乙方文本，生成/刷新待认领草案。
 *
 * ⚠ 这是**会写库的运维动作**：只读合同表、只写草案表；返回体 `data` = 本次新建或刷新的草案，
 *   另有顶层 `count` = 条数（**数据无变化时 count=0**，这正是"重复扫描幂等"的可观察形态，
 *   不是"草案总数"—— 总数看草案列表接口）。
 */
export function scanPartyDrafts() {
  return request({ url: '/ctms/migration/scan', method: 'post' })
}

/**
 * 草案列表（分页，按 create_time desc）。
 *
 * @param {object} query { pageNum, pageSize, partyType: customer|supplier,
 *                         rawName: 模糊, status: pending|claimed|ignored|all, matchedId }
 *   ⚠ `status=all` 由**服务层**归一为"不过滤"（OGNL 把单引号字面量当 Character，
 *     不能把 'all' 写进 XML）；传空串/不传同样是不过滤。
 */
export function listPartyDrafts(query) {
  return request({ url: '/ctms/migration/drafts', method: 'get', params: query })
}

/**
 * 认领一条草案并按原始名称批量回填历史合同。
 *
 * 请求体两种形态（二选一，见 CtmsMigrationClaimVo）：
 *   · 绑定已有档案：`{ partyId }`
 *   · 新建档案后绑定：`{ code（必填）, name?, shortName?, taxNo?, contactName?, contactPhone?,
 *                       address?, bankName?, bankAccount?, creditLimit?, level?,
 *                       supplyScope?, paymentDays? }`（客户方向忽略后两个）
 * 另有 `remark` 写入草案备注。
 *
 * 返回 `data` = 认领后的草案：`status=claimed`、`matchedId`、`contractCount` = **本次实绑数**。
 * ⚠ 幂等语义由状态机给：已认领的草案再提交会被拒（提示含「已认领」）。
 */
export function claimPartyDraft(id, data) {
  return request({ url: '/ctms/migration/drafts/' + id + '/claim', method: 'post', data: data })
}

/**
 * 忽略一条草案（`remark` 为**查询串**参数，可空，写入草案备注）。
 *
 * 已忽略的草案重复忽略是幂等的（后一次 remark 覆盖前一次）；已认领的草案拒绝忽略
 * （它已经把档案引用写进合同，"忽略"会让状态与事实矛盾）→ 文案「该草案已认领，不能忽略」。
 * 被忽略的草案不会再被扫描复活；历史合同的文本原样保留、继续正常展示。
 */
export function ignorePartyDraft(id, remark) {
  return request({ url: '/ctms/migration/drafts/' + id + '/ignore', method: 'post', params: { remark: remark } })
}
