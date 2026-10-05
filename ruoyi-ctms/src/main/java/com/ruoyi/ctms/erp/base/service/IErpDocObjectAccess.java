package com.ruoyi.ctms.erp.base.service;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> <b>单据对象的"存在性 + 数据范围"校验接口</b>（2.0 B4 任务 1.4；附件 notes §2 的接入点 ②）。 </p>
 *
 * <p> <b>为什么抽成接口</b>：附件服务（B3 交付的 {@code CtmsAttachmentServiceImpl}）是
 * <b>多态挂载</b>的：它只知道 {@code object_type} + {@code object_id}，需要有人告诉它
 * "这个采购单/入库单是否存在、当前用户能不能看"。这个判定属于 B4 的单据域，
 * 不该写在附件服务里，也不该让它 import 8 个单据服务；于是定义接口，
 * 由 B4 的公共实现（{@code ErpDocObjectAccessServiceImpl}）按单据类型统一处理，
 * 附件服务只做一次分派。 </p>
 *
 * <p> <b>失败必须是明确的</b>：未注册类型抛"未注册的对象类型"、对象不存在抛
 * "{单据名}不存在"、范围外抛业务码 <b>403</b>（返回体 {@code {"code":403,...}}，
 * 与 {@code tools/authz-check.ps1} 的 {@code IsForbidden} 形态一致）。
 * <b>绝不允许"注册了类型但校验分支缺失就静默放过"</b> —— 那会产生指向不存在对象的孤儿附件。 </p>
 *
 * @author 二开
 */
public interface IErpDocObjectAccess
{
    /**
     * 本实现是否负责该对象类型。
     *
     * @param objectType 对象类型（已归一为小写）
     * @return 负责返回 true
     */
    boolean supports(String objectType);

    /**
     * 校验对象存在且当前用户可见（不可见时抛 403）。
     *
     * @param objectType 对象类型（已归一为小写）
     * @param objectId   对象标识
     * @throws ServiceException 类型未知 / 对象不存在 / 超出数据范围（403）
     */
    void checkObjectAccess(String objectType, String objectId);
}
