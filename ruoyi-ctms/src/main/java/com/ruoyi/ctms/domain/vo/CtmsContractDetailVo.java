package com.ruoyi.ctms.domain.vo;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.ctms.domain.CtmsContract;

/**
 * <p> 合同详情的返回对象：在 {@link CtmsContract} 之上只加一个<b>只读</b>区块
 * {@code relatedDocs}（2.0 B3 任务 4.7）。 </p>
 *
 * <p> <b>为什么不把 relatedDocs 加到 CtmsContract 上</b>：{@code CtmsContract} 是 Mapper 的
 * 结果类型，往它上面挂一个"不来自任何表"的字段会让列表查询的结果里出现永久为空的列，
 * 也会让"合同对象 == 数据行"这条约定变模糊。继承一个 VO 是这里更小的改动面。 </p>
 *
 * <p> <b>B3 阶段 relatedDocs 固定为空列表</b>：关联单据（采购/销售/出入库）属 B4，
 * B3 还没有 {@code t_erp_*} 表。B4 交付单据域后在此处接只读查询，
 * 且 MUST NOT 触发对合同任何字段的回写。 </p>
 *
 * @author 二开
 */
public class CtmsContractDetailVo extends CtmsContract
{
    private static final long serialVersionUID = 1L;

    /** 只读「关联单据」列表（B3 固定空列表） */
    private List<CtmsRelatedDocVo> relatedDocs = new ArrayList<>();

    public List<CtmsRelatedDocVo> getRelatedDocs()
    {
        return relatedDocs;
    }

    public void setRelatedDocs(List<CtmsRelatedDocVo> relatedDocs)
    {
        this.relatedDocs = relatedDocs;
    }
}
