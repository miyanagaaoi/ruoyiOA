package com.ruoyi.ctms.erp.procurement.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;

/**
 * <p> 采购申请单主体的数据访问接口（XML：{@code resources/mapper/erp/ErpPurRequestMapper.xml}）。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> {@link #selectRequestList} 承担「状态筛选 + 默认排除已作废 + 关键词 + 日期区间 +
 *        数据范围片段」；默认排除的判定用 {@code includeVoidedForQuery}/{@code status}
 *        两个条件表达（与 {@code CtmsContractMapper} 的 {@code includeDeleted} 同款写法）；</li>
 *   <li> <b>金额不在本层算</b>：{@code t_ctms_purchase_request} 没有 {@code total_amount} 列，
 *        金额由服务层按「先舍入再汇总」现算（{@code ErpAmounts}）；</li>
 *   <li> 单号唯一：{@link #selectDocNoByNo} <b>不排除</b>已作废/已删除行（占号不复用）；</li>
 *   <li> 软删除与作废都落在 {@code status} / {@code del_flag}，各有专用方法。</li>
 * </ul>
 *
 * @author 二开
 */
public interface ErpPurchaseRequestMapper
{
    /**
     * 采购申请单列表（状态、关键词、单据日期区间、需求部门、创建人、包含已作废、数据范围片段）。
     *
     * @param query 查询条件（{@code dataScopeSql} 由服务端拼出）
     * @return 单据集合（单据日期倒序、同日按单号倒序）
     */
    List<ErpPurchaseRequest> selectRequestList(ErpPurchaseRequest query);

    /**
     * 按主键查单据（<b>含已作废与已软删除行</b>：状态机与幂等判定都要先看到它们）。
     *
     * @param id 主键
     * @return 单据；不存在返回 null
     */
    ErpPurchaseRequest selectRequestById(@Param("id") String id);

    /**
     * 按单号查单据（查重与"占号不复用"判定；刻意不过滤状态与 {@code del_flag}）。
     *
     * @param docNo 单号
     * @return 单据；不存在返回 null
     */
    ErpPurchaseRequest selectDocNoByNo(@Param("docNo") String docNo);

    /**
     * 取库内以给定前缀开头的全部单号（本地兜底单号的"同桶 max + 1"依据）。
     *
     * @param prefix 前缀（形如 {@code CGSQ20261005}；由服务端拼出，不含用户输入）
     * @return 单号集合（可能为空，非 null）
     */
    List<String> selectDocNosByPrefix(@Param("prefix") String prefix);

    /**
     * 新增单据（{@code id} 由服务层生成；{@code create_time} 走库默认值）。
     *
     * @param request 单据
     * @return 影响行数
     */
    int insertRequest(ErpPurchaseRequest request);

    /**
     * 修改单据（只覆盖可编辑的业务列；{@code doc_no} / {@code del_flag} / 痕迹列由专用方法写）。
     *
     * @param request 单据
     * @return 影响行数
     */
    int updateRequest(ErpPurchaseRequest request);

    /**
     * 写状态与痕迹列（状态流转的唯一出口：提交/审核/驳回/作废/反审核/自动完成）。
     *
     * @param request 单据（须含 id 与要写入的列）
     * @return 影响行数
     */
    int updateRequestStatus(ErpPurchaseRequest request);

    /**
     * <p> 清空审核痕迹（{@code approved_by} / {@code approved_at} 置 NULL）。 </p>
     *
     * <p> 反审核专用：DDL 注释明确"反审核时置空"，而"置空"用通用 {@code <if test="x != null">}
     * 写不出来（null 会被跳过，痕迹留在库里就成了"已审核但状态是待审核"的假象）。 </p>
     *
     * @param id       单据ID
     * @param updateId 更新人用户ID
     * @param updateBy 更新人登录名快照
     * @return 影响行数
     */
    int clearApprovalTrace(@Param("id") String id, @Param("updateId") String updateId,
                           @Param("updateBy") String updateBy);

    /**
     * 物理删除单据（仅回滚演练与夹具清理；业务侧一律走作废）。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteRequestById(@Param("id") String id);
}
