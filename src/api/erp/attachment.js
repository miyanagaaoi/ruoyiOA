import docKinds from '@/views/erp/doc/doc-kinds'
import {
  listAttachment,
  uploadAttachment,
  downloadAttachment,
  delAttachment,
  getAttachmentObjectTypes
} from '@/api/ctms/attachment'

/**
 * 单据附件（B4 §8.3 的"附件区"）：**复用 B3 的通用附件接口**，不新增端点。
 *
 * 口径与依据：
 *   · 挂载协议是 `object_type + object_id`；单据的对象类型码与 `ErpDocType.code` **逐字相同**
 *     （附件对象类型清单里 B4 追加的就是这些码）。
 *   · 读写权限仍**由服务端按对象类型判定**（参考仓库 `OBJECT_PERMS` 的同款语义：
 *     上传/删除需要"该业务对象的 edit" + 附件读权限；`v-hasPermi` 只改善体验）。
 *     ⚠ 本批次里 B4 的对象类型由 T1 在 `CtmsAttachmentObjectTypes` 中登记；
 *       文档（`notes/09a-frontend-core.md` §5）已记录一个待办：
 *       `purchase_request` / `sales_request` 目前**不在**该清单里（清单只有 5 个 planned + 调拨），
 *       这两个页面的附件区会在服务端收到 422，需要 T1 补登记。
 */

export { listAttachment, uploadAttachment, downloadAttachment, delAttachment, getAttachmentObjectTypes }

/** 单据码 → 附件对象类型（两者必须一致；这里集中一处，避免页面各写一份映射）。 */
export function objectTypeOf(kindCode) {
  const kind = docKinds.getKind(kindCode)
  return kind ? kind.attachmentObjectType : ''
}
