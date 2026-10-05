package com.ruoyi.ctms.erp.posting.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ruoyi.ctms.erp.base.ErpDocType;

/**
 * <p> <b>一次过账/红冲的入参</b>（2.0 B4 任务 5.2/5.4）。 </p>
 *
 * <p> 归一到"单据身份 + 业务类型 + 操作人 + 行指令"四组：
 * 入库单、出库单、盘点生成的调整单、调拨（两次调用：调出仓负数、调入仓正数）
 * 都用同一个对象过账，于是幂等（{@code posted} 标记由调用方读取）、
 * 流水字段（{@code doc_type}/{@code doc_id}/{@code doc_no}/{@code biz_type}）
 * 与变动后结存快照的口径只有一份。 </p>
 *
 * @author 二开
 */
public class ErpPostingRequest
{
    /** 单据类型（落流水的 {@code doc_type}）。 */
    private ErpDocType docType;

    /** 单据ID（落流水的 {@code doc_id}）。 */
    private String docId;

    /** 单据号（落流水的 {@code doc_no}）。 */
    private String docNo;

    /** 来源单号快照（可空）。 */
    private String srcDocNo;

    /** 业务类型（落流水的 {@code biz_type}；如 {@code 采购入库} / {@code 销售出库}）。 */
    private String bizType;

    /** 归属部门快照（落流水的 {@code dept_id}；数据范围判定用）。 */
    private String deptId;

    /** 操作人用户ID（落流水的 {@code create_id}）。 */
    private String operatorId;

    /** 操作人登录名快照（落流水的 {@code create_by}）。 */
    private String operatorName;

    /** 流水备注（可空）。 */
    private String remark;

    /** 过账选项（负库存校验的相关例外）。 */
    private ErpPostingOptions options = ErpPostingOptions.defaults();

    /** 行指令集合（可含同一（物料, 仓库）的多行，按行号顺序处理）。 */
    private List<ErpPostingLine> lines = new ArrayList<>();

    public ErpDocType getDocType()
    {
        return docType;
    }

    public ErpPostingRequest setDocType(ErpDocType docType)
    {
        this.docType = docType;
        return this;
    }

    public String getDocId()
    {
        return docId;
    }

    public ErpPostingRequest setDocId(String docId)
    {
        this.docId = docId;
        return this;
    }

    public String getDocNo()
    {
        return docNo;
    }

    public ErpPostingRequest setDocNo(String docNo)
    {
        this.docNo = docNo;
        return this;
    }

    public String getSrcDocNo()
    {
        return srcDocNo;
    }

    public ErpPostingRequest setSrcDocNo(String srcDocNo)
    {
        this.srcDocNo = srcDocNo;
        return this;
    }

    public String getBizType()
    {
        return bizType;
    }

    public ErpPostingRequest setBizType(String bizType)
    {
        this.bizType = bizType;
        return this;
    }

    public String getDeptId()
    {
        return deptId;
    }

    public ErpPostingRequest setDeptId(String deptId)
    {
        this.deptId = deptId;
        return this;
    }

    public String getOperatorId()
    {
        return operatorId;
    }

    public ErpPostingRequest setOperatorId(String operatorId)
    {
        this.operatorId = operatorId;
        return this;
    }

    public String getOperatorName()
    {
        return operatorName;
    }

    public ErpPostingRequest setOperatorName(String operatorName)
    {
        this.operatorName = operatorName;
        return this;
    }

    public String getRemark()
    {
        return remark;
    }

    public ErpPostingRequest setRemark(String remark)
    {
        this.remark = remark;
        return this;
    }

    public ErpPostingOptions getOptions()
    {
        return options;
    }

    public ErpPostingRequest setOptions(ErpPostingOptions options)
    {
        this.options = options == null ? ErpPostingOptions.defaults() : options;
        return this;
    }

    public List<ErpPostingLine> getLines()
    {
        return lines;
    }

    /**
     * 设置行指令（null 视为空集合；过账引擎会拒绝空集合）。
     *
     * @param lines 行指令
     * @return 本对象
     */
    public ErpPostingRequest setLines(List<ErpPostingLine> lines)
    {
        this.lines = lines == null ? new ArrayList<ErpPostingLine>() : lines;
        return this;
    }

    /**
     * 只读行指令视图。
     *
     * @return 只读列表
     */
    public List<ErpPostingLine> linesView()
    {
        return Collections.unmodifiableList(lines);
    }

    /**
     * 单据标识（{@code docType:docId}，日志与错误文案用）。
     *
     * @return 标识
     */
    public String docKey()
    {
        String type = docType == null ? "?" : docType.getCode();
        return type + ":" + docId;
    }

    @Override
    public String toString()
    {
        return "ErpPostingRequest{docKey=" + docKey() + ", bizType='" + bizType + "', lines=" + lines.size()
                + ", options=" + options + '}';
    }
}
