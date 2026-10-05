# `mapper/ctms/` —— 合同台账域的 Mapper XML

## 命名硬规则

XML 文件名**必须以 `Mapper.xml` 结尾**（如 `CtmsContractMapper.xml`）。

原因：MyBatis 的扫描规则写在
`ruoyi-admin/src/main/resources/env/dev/application.yml`：

```yaml
mybatis:
  mapperLocations: classpath*:mapper/**/*Mapper.xml
```

不符合这个命名的 XML **不会被加载**，症状是启动正常、调用时报
`Invalid bound statement (not found)` —— 很容易误判成"接口写错了"。
（`ruoyi-workflow` 的 `PrintTemplateMapper.xml`、`ruoyi-kbs` 的 `mapper/document/*Mapper.xml`
都是这个约定，可以直接对照。）

Mapper **接口**放 `com.ruoyi.ctms.mapper`，由全局 `@MapperScan("com.ruoyi.**.mapper")`
（`ruoyi-framework/.../config/ApplicationConfig.java:19`）自动注册。

## 数据范围（design.md D-4）

本域**不复用** RuoYi 的 `@DataScope`（它只覆盖系统模块 5 处，且 `SELF`/`DEPT` 语义与业务口径不等价）。

口径（服务端强制、多角色取**并集**）：

| 档位 | 含义 |
| --- | --- |
| `SELF` | 创建人本人 **或** 创建人所属部门 |
| `DEPT` | 合同 `dept_id` 命中当前用户部门的 `sys_dept.ancestors` 链（**含下级为默认**，可配置关闭） |
| `ALL` | 不加条件 |

实现要求：

1. SQL 片段写成"**命中即放行**"的多路 `OR`（参照
   `ruoyi-workflow/src/main/resources/mapper/workflow/AccessCheckMapper.xml` 的 union 范式），
   而不是逐档拼字符串 —— 后者在多角色并集下极易漏掉一档；
2. 该片段必须被**列表 / 详情 / 编辑 / 删除 / 恢复 / 导出**共用，
   不允许各写一份（口径分散是本批次最高风险项 R-5）；
3. 范围外按标识直查要返回 **403**（规格场景），所以判定放在服务层，不在控制器。

## 待建 XML 清单（B3）

| XML | 承载 |
| --- | --- |
| `CtmsContractMapper.xml` | 合同主体：多维筛选、标签交集、框架树、软删除/恢复、数据范围片段 |
| `CtmsContractItemMapper.xml` | 行项（含物料快照） |
| `CtmsTagMapper.xml` / `CtmsContractTagMapper.xml` | 标签与合同标签（只追加关系） |
| `CtmsChangeLogMapper.xml` | 字段级变更历史（对象类型 + 对象标识） |
| `CtmsAttachmentMapper.xml` | 附件挂载元数据（`(object_type, object_id)` 复合索引） |
| `CtmsCustomerMapper.xml` / `CtmsSupplierMapper.xml` | 往来单位档案 |
| `CtmsPartyDraftMapper.xml` | 历史文本待认领草案 |
| `CtmsProductTypeMapper.xml` / `CtmsUomMapper.xml` / `CtmsWarehouseMapper.xml` / `CtmsProductMapper.xml` | 物料域主数据（B4 复用） |
