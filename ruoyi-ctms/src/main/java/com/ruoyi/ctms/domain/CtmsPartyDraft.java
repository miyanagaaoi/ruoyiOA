package com.ruoyi.ctms.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 历史甲乙方迁移草案对象，对应表 {@code t_ctms_party_draft}（<b>13 列</b>；第 2 组 2.1 建表、
 * 第 7 组 7.1 起使用；DDL 见 {@code sql/二开-合同台账.sql} 第 262~282 行，
 * 列级评审见 {@code notes/ddl-review.md} §1.9）。 </p>
 *
 * <p> 用途：把"有文本、没有档案引用"的历史合同按「档案方向 + 原始文本」聚合，
 * 形成一张待人工认领的清单（规格 {@code ctms/contract-migration} 的
 * 「历史甲乙方文本扫描生成待认领草案」）；认领后按原始名称批量回填合同的档案引用。 </p>
 *
 * <p> <b>字段与 DDL 逐列对应</b>（13 列）：{@code id} / {@code party_type} / {@code raw_name} /
 * {@code contract_count} / {@code status} / {@code matched_id} / {@code remark} /
 * {@code create_time} / {@code update_time} / {@code create_id} / {@code create_by} /
 * {@code update_id} / {@code update_by}。
 * {@code createBy}/{@code createTime}/{@code updateBy}/{@code updateTime}/{@code remark} 由
 * {@link BaseEntity} 提供，本类<b>不重复声明</b>；{@code createId}/{@code updateId} 基类里没有，
 * 因此单独声明（与 {@code CtmsContract}/{@code CtmsChangeLog} 同款）。 </p>
 *
 * <p> ⚠ <b>{@code createId} / {@code createBy} 必须保持可空</b>（DDL 里就是 {@code DEFAULT NULL}）：
 * 草案由<b>扫描任务</b>生成，可能是无登录上下文的运维/定时运行，收紧非空会把"系统扫描"变成
 * 只能由登录用户点一次。取值兜底放在服务层（无上下文时回落 {@code 1}/{@code system}），
 * 列本身不得收紧 —— 这是任务 2.4 的明文口径（「{@code t_ctms_party_draft.matched_id} 与各类仅用于
 * 反查名称的引用列保持可空」）。 </p>
 *
 * <p> ⚠ <b>{@code matchedId} 是多态列</b>：按 {@code partyType} 分别指向 {@code t_ctms_customer}
 * 或 {@code t_ctms_supplier}，因此<b>不能</b>加单一外键（{@code notes/ddl-review.md} §4.2-7），
 * 库里只给它加了索引。 </p>
 *
 * <p> ⚠ <b>本表没有 {@code del_flag}</b>：草案靠 {@code status} 流转（待认领/已认领/已忽略），
 * 不引入第二套软删除；{@code status} 是<b>状态枚举</b>而不是启用标志，所以也不做 {@code char(1)} 映射
 * （与主数据 {@code enable_flag} 的语义不同，见 {@code ddl-review.md} §1.9）。 </p>
 *
 * <p> <b>幂等键</b>是数据库唯一索引 {@code uk_party_draft(party_type, raw_name)}（Q12 裁决新增：
 * 同一方向 + 同一原始文本在库内只允许一条草案；重复扫描只能刷新计数，不能新建）。
 * 该索引落在 {@code utf8mb4_0900_ai_ci} 上，是<b>大小写/重音不敏感</b>的比较，
 * 聚合口径见 {@code MigrationRules.rawNameKey}。 </p>
 *
 * @author 二开
 */
public class CtmsPartyDraft extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID，由服务层生成后传入 Mapper） */
    private String id;

    /** 档案方向：{@code customer}-客户 / {@code supplier}-供应商（取值常量见 {@code MigrationRules}） */
    private String partyType;

    /** 合同甲/乙方原始文本（与 {@code partyType} 共同构成幂等键） */
    private String rawName;

    /** 关联合同数量（重复扫描只刷新该计数，不新建草案） */
    private Integer contractCount;

    /** 草案状态：{@code pending}-待认领 / {@code claimed}-已认领 / {@code ignored}-已忽略 */
    private String status;

    /** 认领后的档案ID（多态：按 {@code partyType} 指向客户或供应商；保持可空、仅加索引、不加 FK） */
    private String matchedId;

    /** 创建人用户ID（扫描任务生成，可能无登录用户 → 保持可空） */
    private String createId;

    /** 更新人用户ID（状态流转由人工认领触发，列本身保持可空） */
    private String updateId;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getPartyType()
    {
        return partyType;
    }

    public void setPartyType(String partyType)
    {
        this.partyType = partyType;
    }

    public String getRawName()
    {
        return rawName;
    }

    public void setRawName(String rawName)
    {
        this.rawName = rawName;
    }

    public Integer getContractCount()
    {
        return contractCount;
    }

    public void setContractCount(Integer contractCount)
    {
        this.contractCount = contractCount;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getMatchedId()
    {
        return matchedId;
    }

    public void setMatchedId(String matchedId)
    {
        this.matchedId = matchedId;
    }

    public String getCreateId()
    {
        return createId;
    }

    public void setCreateId(String createId)
    {
        this.createId = createId;
    }

    public String getUpdateId()
    {
        return updateId;
    }

    public void setUpdateId(String updateId)
    {
        this.updateId = updateId;
    }

    @Override
    public String toString()
    {
        return "CtmsPartyDraft{" +
                "id='" + id + '\'' +
                ", partyType='" + partyType + '\'' +
                ", rawName='" + rawName + '\'' +
                ", contractCount=" + contractCount +
                ", status='" + status + '\'' +
                ", matchedId='" + matchedId + '\'' +
                ", createId='" + createId + '\'' +
                ", updateId='" + updateId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", remark='" + getRemark() + '\'' +
                '}';
    }
}
