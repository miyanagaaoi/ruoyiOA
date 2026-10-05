package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.CommonForm;

/**
 * <p> 「关联审批」控件的服务端校验与落库钩子（2.0 B1 §7，REQ-FORM-010 / AC-53..AC-55） </p>
 *
 * <p> 为什么用接口 + 运行期取实现：关联关系的表与"候选单据"的口径都在
 * {@code ruoyi-workflow}，而 {@code ruoyi-biz-sdk} **不能反向依赖**它
 * （与 {@link INodeFieldWriteGuard} 同一套做法，见 {@code ApplicationContextHelper}）。 </p>
 *
 * <p> 实现方不存在时（裁剪部署）应静默跳过 —— 关联审批不是流程主链路的必需项。 </p>
 *
 * @author 二开
 */
public interface IRelatedApprovalGuard {

    /**
     * 服务端复核「关联审批」的选择范围。
     *
     * <p> 前端只画了候选下拉，绕过去直接 POST 一个不在候选范围内的 {@code businessId}
     * 就能把任意单据挂到自己单子上（连带把别人的单据号暴露出去）——
     * 所以范围必须由服务端判：候选人 = 与被关联模板**同一分组**、且**本人发起**的单据。 </p>
     *
     * @param commonForm 表单提交参数（含 templateId 与 valData）
     * @throws com.ruoyi.common.exception.base.BaseException 选择越界时抛出（提交被拒绝）
     */
    void validate(CommonForm commonForm);

    /**
     * 落库关联关系（全量替换该单据的关系行，含被关联单据号快照）。
     *
     * <p> 在单据本身写入成功之后调用；{@code businessId} 用本次生成的单据ID。 </p>
     *
     * @param commonForm 表单提交参数
     * @param businessId 宿主单据的业务ID
     */
    void persist(CommonForm commonForm, String businessId);
}
