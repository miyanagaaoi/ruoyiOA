package com.ruoyi.ctms.erp.procurement.support;

import java.util.Date;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocNoGenerator;
import com.ruoyi.ctms.erp.base.ErpDocType;

/**
 * <p> <b>采购线的取号入口：委托 base 的唯一取号点，只额外加"撞号重试"。</b> </p>
 *
 * <p> <b>铁律（简报 §9）</b>：单号<b>只能</b>走平台 {@code ruoyi-serial}，
 * 由 base 的 {@link ErpDocNoGenerator#nextDocNo(ErpDocType, Date)} 统一取号
 * （配置 id、前缀 {@code PR}/{@code PO}、{@code yyyyMM} 分桶都在那里冻结）。
 * 本组<b>不自算</b>"前缀 + 库内最大号 + 1"，不解析单号形态。 </p>
 *
 * <p> <b>本类唯一的增量是"撞号重试"</b>（为什么放在这里而不是 base：base 的取号是
 * 纯平台能力，不该关心"库内单号是否已被占用"这一业务事实；而单号列有唯一索引，
 * 一旦编号服务的计数器落后于库内水位（Redis 被清空 / 从快照恢复库 / 配置被重建），
 * 不重试就会变成数据库 1062（500）。重试是<b>碰撞恢复</b>，代价只是几个号被跳过）。 </p>
 *
 * @author 二开
 */
public class ErpPurDocNoGenerator
{
    /** 撞号重试上限（宁可失败也不无限循环）。 */
    private static final int RETRY_LIMIT = 50;

    /** base 的统一取号器（可为 null：未装配时取号显式失败，绝不静默返回空号）。 */
    private final ErpDocNoGenerator delegate;

    /**
     * @param delegate base 的统一取号器（{@code ErpDocNoGenerator}）
     */
    public ErpPurDocNoGenerator(ErpDocNoGenerator delegate)
    {
        this.delegate = delegate;
    }

    /**
     * 单号占用判定回调（由调用方用"按单号查本表"实现；单测给 lambda）。
     */
    public interface DocNoTaken
    {
        /**
         * @param docNo 候选单号
         * @return 已占用返回 true
         */
        boolean isTaken(String docNo);
    }

    /**
     * 取一个未被占用的单号。
     *
     * @param docType 单据类型（{@code purchase_request} / {@code purchase_order}）
     * @param docDate 单据日期（编号里的年月取它；为空由 base 取当天）
     * @param taken   占用判定回调（不得为 null）
     * @return 单号（形如 {@code PR202610000001}）
     * @throws ServiceException 取号器未装配 / 连续撞号超过上限
     */
    public String nextDocNo(ErpDocType docType, Date docDate, DocNoTaken taken)
    {
        if (docType == null)
        {
            throw new ServiceException("未指定单据类型，无法取号");
        }
        if (taken == null)
        {
            throw new ServiceException("单号占用判定回调不能为空");
        }
        if (delegate == null)
        {
            throw new ServiceException("编号服务未装配，无法为" + docType.getLabel() + "取号");
        }
        for (int attempt = 0; attempt < RETRY_LIMIT; attempt++)
        {
            String candidate = delegate.nextDocNo(docType, docDate);
            if (!taken.isTaken(candidate))
            {
                return candidate;
            }
        }
        throw new ServiceException(docType.getLabel() + "取号失败：连续 " + RETRY_LIMIT
                + " 次都撞上已占用单号，请检查编号配置（t_code_config / t_code_sequence_log）");
    }

    /**
     * 预览下一个单号（不占号；表单展示用）。
     *
     * @param docType 单据类型
     * @param docDate 参考日期
     * @return 单号
     */
    public String previewDocNo(ErpDocType docType, Date docDate)
    {
        if (delegate == null)
        {
            throw new ServiceException("编号服务未装配，无法预览单号");
        }
        return delegate.previewDocNo(docType, docDate);
    }
}
