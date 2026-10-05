package com.ruoyi.ctms.erp.stockops.domain;

import java.util.Date;
import java.util.List;

import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

/**
 * <p> <b>调拨单表头</b>（表 {@code t_ctms_transfer}，2.0 B4 任务 6.1/6.2；
 * DDL 见 {@code sql/二开-进销存.sql} 第 469~516 行）。 </p>
 *
 * <p> 公共列（单号/日期/状态/归属部门/创建人快照/经办人/合同/来源三列/痕迹列/过账标记/软删除）
 * 全部来自 {@link ErpDocHeader}；本类只声明调拨特有的两仓列与查询条件。 </p>
 *
 * <p> <b>四条口径</b>（评审时对照 AC-75 / REQ-STK-004）： </p>
 * <ol>
 *   <li> {@code fromWarehouseId} / {@code toWarehouseId} 是<b>表头</b>两仓（DDL 级 NOT NULL + 外键），
 *        必须非空且不同（"两仓相同"在保存与审核两处都被拒；前端拦截只是体验）； </li>
 *   <li> 行项<b>不使用行级仓库</b>（{@code t_ctms_transfer_item.warehouse_id} 恒为 null）； </li>
 *   <li> {@code posted} 是过账幂等依据：已过账再审核直接短路；反审核红冲两条流水； </li>
 *   <li> 调拨不产生金额（行项单价/金额恒 0，表头没有金额列）。 </li>
 * </ol>
 *
 * <p> {@code items} 是详情/编辑的装配字段（非持久化列）；{@code dataScopeSql} 由服务层用
 * {@code ErpDocScope.buildDataScopeSql("d")} 拼出（服务端白名单，请求参数永远进不去）。 </p>
 *
 * @author 二开
 */
public class ErpTransfer extends ErpDocHeader
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（调拨单特有列） ==================== */

    /** 调出仓库ID（必填）。 */
    private String fromWarehouseId;

    /** 调出仓库名快照。 */
    private String fromWarehouseName;

    /** 调入仓库ID（必填，且必须与调出仓不同）。 */
    private String toWarehouseId;

    /** 调入仓库名快照。 */
    private String toWarehouseName;

    /* ==================== 非持久化字段（装配与查询条件） ==================== */

    /** 行项集合（详情/编辑装配）。 */
    private List<ErpTransferItem> items;

    /** 数据范围片段（服务端拼出，Mapper 用 {@code ${}} 原样拼接）。 */
    private String dataScopeSql;

    /** 查询条件：单据日期起（含）。 */
    private Date beginDocDate;

    /** 查询条件：单据日期止（含）。 */
    private Date endDocDate;

    /** 查询条件：单号/备注/来源单号关键字。 */
    private String keyword;

    /** 查询条件：是否包含已作废（{@code Boolean.TRUE} 才包含；默认排除）。 */
    private Boolean includeVoided;

    public String getFromWarehouseId()
    {
        return fromWarehouseId;
    }

    public void setFromWarehouseId(String fromWarehouseId)
    {
        this.fromWarehouseId = fromWarehouseId;
    }

    public String getFromWarehouseName()
    {
        return fromWarehouseName;
    }

    public void setFromWarehouseName(String fromWarehouseName)
    {
        this.fromWarehouseName = fromWarehouseName;
    }

    public String getToWarehouseId()
    {
        return toWarehouseId;
    }

    public void setToWarehouseId(String toWarehouseId)
    {
        this.toWarehouseId = toWarehouseId;
    }

    public String getToWarehouseName()
    {
        return toWarehouseName;
    }

    public void setToWarehouseName(String toWarehouseName)
    {
        this.toWarehouseName = toWarehouseName;
    }

    public List<ErpTransferItem> getItems()
    {
        return items;
    }

    public void setItems(List<ErpTransferItem> items)
    {
        this.items = items;
    }

    public String getDataScopeSql()
    {
        return dataScopeSql;
    }

    public void setDataScopeSql(String dataScopeSql)
    {
        this.dataScopeSql = dataScopeSql;
    }

    public Date getBeginDocDate()
    {
        return beginDocDate;
    }

    public void setBeginDocDate(Date beginDocDate)
    {
        this.beginDocDate = beginDocDate;
    }

    public Date getEndDocDate()
    {
        return endDocDate;
    }

    public void setEndDocDate(Date endDocDate)
    {
        this.endDocDate = endDocDate;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public Boolean getIncludeVoided()
    {
        return includeVoided;
    }

    public void setIncludeVoided(Boolean includeVoided)
    {
        this.includeVoided = includeVoided;
    }

    @Override
    public String toString()
    {
        return "ErpTransfer{id='" + getId() + "', docNo='" + getDocNo() + "', status='" + getStatus()
                + "', from='" + fromWarehouseId + "', to='" + toWarehouseId + "', posted='" + getPosted() + "'}";
    }
}
