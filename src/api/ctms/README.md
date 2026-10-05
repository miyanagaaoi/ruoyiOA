# `src/api/ctms/` —— 合同台账接口层（2.0 B3）

> 变更集：`openspec/changes/oa-contract-ledger`

## 约定

- 一个后端资源一个文件，与 `src/views/ctms/**` 的目录结构对应：
  `contract.js`（合同 + 行项）、`tag.js`、`partner.js`（客户/供应商）、`migration.js`、`attachment.js`。
- 统一用 `@/utils/request`（它已带 token、统一错误提示与 `code !== 200` 的 reject）。
  **不要**在组件里直接 `import axios`。
- URL 前缀与后端 `@RequestMapping` 对齐：`/ctms/contract/**`、`/ctms/tag/**`、
  `/ctms/partner/**`、`/ctms/migration/**`、`/ctms/attachment/**`。
- 导出走 `request({ responseType: 'blob' })` + `@/utils/ruoyi` 的下载封装（与既有模块一致）。

## ⚠ 两个与本项目强相关的坑（都踩过，见 DEV-ENV.md）

1. **`POST` 与 `GET` 别写反**：本项目有过 `/file/operate/downloadfile` 只接受 POST 的教训；
   写 api 文件时按后端注解逐个核对方法名。
2. **响应体的 `code` 才是业务结果**：后端业务异常时 **HTTP 仍回 200**，
   所以判断成败一律看 `res.code`，不要看 HTTP 状态码。
