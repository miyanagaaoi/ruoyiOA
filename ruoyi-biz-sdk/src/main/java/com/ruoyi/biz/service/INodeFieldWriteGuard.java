package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.CommonForm;

/**
 * <p> 节点级「字段只读」的服务端校验钩子（PRD AC-12） </p>
 *
 * <p> 为什么用接口 + 运行期取实现，而不是直接调用：字段权限表在
 * {@code ruoyi-template}、流程模型在 {@code ruoyi-flowable}，而本接口所在的
 * {@code ruoyi-biz-sdk} **不能反向依赖** {@code ruoyi-workflow}；
 * 所以接口定在这边、实现放在那边，运行期由
 * {@code ApplicationContextHelper} 按类型取（与 {@code BizFlowSubmitFactory} 同一套做法）。 </p>
 *
 * <p> 实现方不存在时应静默跳过（例如裁剪部署）—— 这不是流程主链路的必需项。 </p>
 *
 * @author 二开
 */
public interface INodeFieldWriteGuard {

    /**
     * 校验本次表单写入是否改动了「当前节点只读」的字段。
     *
     * @param commonForm 表单提交参数
     * @throws com.ruoyi.common.exception.base.BaseException 字段被非法修改时抛出
     */
    void check(CommonForm commonForm);
}
