package com.ruoyi.ctms.domain.vo;

/**
 * <p> 合同详情里的<b>只读「关联单据」</b>条目（2.0 B3 任务 4.7）。 </p>
 *
 * <p> <b>B3 阶段没有真实单据表</b>：采购/销售/出入库单据属 B4
 * （{@code oa-purchase-sales-stock}），所以本类只固定三个展示字段，
 * 由 {@code CtmsContractDetailVo.relatedDocs} 承载。 </p>
 *
 * <p> ⚠ 该区块是<b>纯只读</b>：读取详情 MUST NOT 回写合同的任何字段
 * （规格 {@code specs/ctms/contract-ledger} 的「合同详情含只读关联单据区块」）。 </p>
 *
 * @author 二开
 */
public class CtmsRelatedDocVo
{
    /** 单据单号 */
    private String docNo;

    /** 单据类型（B4 起为采购订单 / 销售订单 / 出入库单等） */
    private String docType;

    /** 单据状态 */
    private String status;

    /**
     * 无参构造（序列化需要）。
     */
    public CtmsRelatedDocVo()
    {
    }

    /**
     * 全参构造。
     *
     * @param docNo   单据单号
     * @param docType 单据类型
     * @param status  单据状态
     */
    public CtmsRelatedDocVo(String docNo, String docType, String status)
    {
        this.docNo = docNo;
        this.docType = docType;
        this.status = status;
    }

    public String getDocNo()
    {
        return docNo;
    }

    public void setDocNo(String docNo)
    {
        this.docNo = docNo;
    }

    public String getDocType()
    {
        return docType;
    }

    public void setDocType(String docType)
    {
        this.docType = docType;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    @Override
    public String toString()
    {
        return "CtmsRelatedDocVo{" +
                "docNo='" + docNo + '\'' +
                ", docType='" + docType + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
