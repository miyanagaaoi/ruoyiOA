package com.ruoyi.ctms.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <p> 附件的业务对象类型常量与注册清单（2.0 B3 任务 6.1；B4 任务 1.x 的接入点）。 </p>
 *
 * <p> <b>为什么单独一个类而不是写在实体里</b>：对象类型是"挂载协议"，
 * 它同时被<b>规则层</b>（{@link CtmsAttachmentRules#isRegisteredObjectType} 的注册表）、
 * <b>服务层</b>（存在性校验的分派）、<b>B4 的单据访问校验</b>
 * （{@code com.ruoyi.ctms.erp.base.service.IErpDocObjectAccess}）与<b>文档</b>
 * （{@code notes/attachment-notes.md} 的清单核对"与常量逐项一致"）引用。
 * <b>四方必须来自同一个常量</b>，否则文档与代码会各自漂移。 </p>
 *
 * <p> <b>B4 接入（2026-10-05，本批次）</b>：原来的 5 个 {@code plannedB4} 单据对象类型
 * （{@code purchase_order}/{@code sales_order}/{@code stock_in}/{@code stock_out}/
 * {@code stock_take}）已挪进 {@link #REGISTERED}，并补上 B4 任务 6.6 要求的
 * {@link #STOCK_TRANSFER}（参考仓库缺这个对象类型）。 </p>
 *
 * <p> <b>补登记两个申请单（2026-10-05，任务 t19）</b>：B4 首轮交付漏了
 * {@link #PURCHASE_REQUEST}/{@link #SALES_REQUEST} —— 参考仓库
 * {@code app/routers/attachments.py:35-44} 的 {@code OBJECT_PERMS} 覆盖
 * <b>合同 + 7 类单据</b>（含两个申请单），而我们只登记了合同 + 6 类单据，
 * 于是两个申请单上传附件会被判"未注册的对象类型"。本题已把它们补进 {@link #REGISTERED}，
 * 清单变为 <b>合同 + 8 类单据 = 9 项</b>（比参考侧多 {@code stock_transfer}，见任务 6.6 / Q-B10）。 </p>
 *
 * <p> 三处同步（本类 + 服务层分派 + 文档）的当前状态： </p>
 * <ol>
 *   <li> 本文件的常量 + {@link #REGISTERED}； </li>
 *   <li> 服务层的存在性校验：{@code CtmsAttachmentServiceImpl#requireObjectAccess} 按
 *        {@code IErpDocObjectAccess.supports()} 分派到
 *        {@code com.ruoyi.ctms.erp.base.service.impl.ErpDocObjectAccessServiceImpl}，
 *        后者按 {@code ErpDocType} 查对应的 8 张表并做数据范围判定 —— 两个申请单
 *        在 {@code ErpDocType} 与 {@code ErpDocLookupMapper.xml} 里<b>本来就有分支</b>，
 *        因此本类登记后无需再改服务层； </li>
 *   <li> 文档：{@code notes/01-base.md} 与 {@code 12-attachment-parity.md}（B4 侧）。 </li>
 * </ol>
 *
 * <p> <b>未注册类型的行为是"明确拒绝"</b>：{@code 未注册的对象类型：<值>}（<b>不是</b>静默放过）——
 * 宁可拒绝，也不要产生一条指向不存在对象的孤儿附件。 </p>
 *
 * @author 二开
 */
public final class CtmsAttachmentObjectTypes
{
    /** 合同主体（{@code t_ctms_contract}），B3 已注册。 */
    public static final String CONTRACT = "contract";

    /** B4 采购申请单（{@code t_ctms_purchase_request}）；参考侧 {@code OBJECT_PERMS} 注册，t19 补登记。 */
    public static final String PURCHASE_REQUEST = "purchase_request";

    /** B4 采购单（{@code t_ctms_purchase_order}）。 */
    public static final String PURCHASE_ORDER = "purchase_order";

    /** B4 销售申请单（{@code t_ctms_sales_request}）；参考侧 {@code OBJECT_PERMS} 注册，t19 补登记。 */
    public static final String SALES_REQUEST = "sales_request";

    /** B4 销售订单（{@code t_ctms_sales_order}）。 */
    public static final String SALES_ORDER = "sales_order";

    /** B4 入库单（{@code t_ctms_stock_in}）。 */
    public static final String STOCK_IN = "stock_in";

    /** B4 出库单（{@code t_ctms_stock_out}）。 */
    public static final String STOCK_OUT = "stock_out";

    /** B4 盘点单（{@code t_ctms_stocktake}）。 */
    public static final String STOCK_TAKE = "stock_take";

    /** B4 调拨单（{@code t_ctms_transfer}）；参考仓库缺该项，PRD Q-B10 要求补齐。 */
    public static final String STOCK_TRANSFER = "stock_transfer";

    /**
     * 已登记（可挂附件）的对象类型清单。
     *
     * <p> 顺序即文档展示顺序；注册表判定在 {@link CtmsAttachmentRules}，
     * 后者<b>直接复用本清单</b>，不再自己写一份。 </p>
     */
    private static final List<String> REGISTERED =
            Collections.unmodifiableList(Arrays.asList(CONTRACT, PURCHASE_REQUEST, PURCHASE_ORDER,
                    SALES_REQUEST, SALES_ORDER, STOCK_IN, STOCK_OUT, STOCK_TAKE, STOCK_TRANSFER));

    private CtmsAttachmentObjectTypes()
    {
    }

    /**
     * 已登记的对象类型清单（只读）。
     *
     * @return 对象类型清单（<b>9 项</b>：合同 + 8 类单据）
     */
    public static List<String> registered()
    {
        return REGISTERED;
    }

    /**
     * B4 的单据对象类型清单（只读；**与 {@link #registered()} 无关**，仅用于核对与文档）。
     *
     * <p> 之所以单独留一份：验收脚本与文档要能一眼看出"8 类单据都登记了"，
     * 而 {@link #registered()} 里还混着 B3 的合同对象。 </p>
     *
     * <p> <b>恒等式</b>（单测与验收脚本都按它断言）：{@code registered().size() ==
     * docObjectTypes().size() + 1}，且 {@code registered() = [CONTRACT] + docObjectTypes()}。 </p>
     *
     * @return 8 类单据对象类型
     */
    public static List<String> docObjectTypes()
    {
        return Collections.unmodifiableList(Arrays.asList(PURCHASE_REQUEST, PURCHASE_ORDER,
                SALES_REQUEST, SALES_ORDER, STOCK_IN, STOCK_OUT, STOCK_TAKE, STOCK_TRANSFER));
    }

    /**
     * <p> 尚未放行、但已确定要接入的对象类型清单。 </p>
     *
     * <p> <b>历史沿革</b>：B3 交付时这里是 5 个 B4 单据对象类型（{@code plannedB4}），
     * 刻意"登记但在判定里不放行"；B4 交付（2026-10-05）已把 5 个全部挪进
     * {@link #REGISTERED} 并补了 {@code stock_transfer} 与两个申请单（t19），故本清单<b>已清空</b>。 </p>
     *
     * <p> {@code GET /ctms/attachment/object-types} 仍返回 {@code plannedB4} 这个键
     * （键名保持不变，避免破坏既有消费者），其值为<b>空数组</b> —— 这正是
     * "B4 待补项已全部交付"的可观察证据。 </p>
     */
    private static final List<String> PLANNED_B4 = Collections.unmodifiableList(
            Arrays.<String>asList());

    /**
     * 待接入的对象类型清单（只读；B4 交付后为空）。
     *
     * @return 待接入对象类型清单（当前为空）
     */
    public static List<String> plannedB4()
    {
        return PLANNED_B4;
    }
}
