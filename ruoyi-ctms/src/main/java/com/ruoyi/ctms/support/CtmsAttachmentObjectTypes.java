package com.ruoyi.ctms.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <p> 附件的业务对象类型常量与注册清单（2.0 B3 任务 6.1；design D-5）。 </p>
 *
 * <p> <b>为什么单独一个类而不是写在实体里</b>：对象类型是"挂载协议"，
 * 它同时被<b>规则层</b>（{@link CtmsAttachmentRules#isRegisteredObjectType} 的注册表）、
 * <b>服务层</b>（存在性校验的分派）与<b>文档</b>（{@code notes/attachment-notes.md} 的
 * 清单核对"与常量逐项一致"）引用。<b>三方必须来自同一个常量</b>，
 * 否则文档与代码会各自漂移。 </p>
 *
 * <p> <b>B4 的追加点就在这一个文件</b>：单据侧对象（采购/销售/出入库单据）交付时，
 * 在此追加常量 + 在 {@link #REGISTERED} 里登记 + 在服务层补存在性校验分支。
 * 未登记的类型会被 {@link CtmsAttachmentRules#checkObjectType} 直接拒绝，
 * 不会产生指向不存在对象的孤儿附件。 </p>
 *
 * @author 二开
 */
public final class CtmsAttachmentObjectTypes
{
    /** 合同主体（{@code t_ctms_contract}），B3 已注册。 */
    public static final String CONTRACT = "contract";

    /**
     * 已登记（可挂附件）的对象类型清单。
     *
     * <p> 顺序即文档展示顺序；B4 追加时<b>同时</b>改这里与
     * {@link CtmsAttachmentRules} 的注册表判定（后者直接复用本清单）。 </p>
     */
    private static final List<String> REGISTERED =
            Collections.unmodifiableList(Arrays.asList(CONTRACT));

    private CtmsAttachmentObjectTypes()
    {
    }

    /**
     * 已登记的对象类型清单（只读）。
     *
     * @return 对象类型清单
     */
    public static List<String> registered()
    {
        return REGISTERED;
    }

    /**
     * 尚未交付、但已确定要接入的对象类型（B4）。
     *
     * <p> 刻意与 {@link #REGISTERED} 分开：这些类型<b>现在必须被拒绝</b>，
     * 但文档要写明"它们将来会加进来"，否则读文档的人会以为遗漏了。 </p>
     */
    private static final List<String> PLANNED_B4 =
            Collections.unmodifiableList(Arrays.asList(
                    "purchase_order", "sales_order", "stock_in", "stock_out", "stock_take"));

    /**
     * B4 待补的对象类型清单（只读；仅用于文档与核对，<b>不参与放行判定</b>）。
     *
     * @return 待补对象类型清单
     */
    public static List<String> plannedB4()
    {
        return PLANNED_B4;
    }
}
