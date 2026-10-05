package com.ruoyi.workflow.related.mapper;

import com.ruoyi.workflow.related.domain.RelatedApproval;
import com.ruoyi.workflow.related.domain.RelatedApprovalCandidate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p> 单据关联关系 Mapper（2.0 B1 §7） </p>
 *
 * @author 二开
 */
public interface RelatedApprovalMapper {

    int batchInsert(@Param("rows") List<RelatedApproval> rows);

    int deleteByBusinessId(@Param("businessId") String businessId);

    /** 宿主单据 -> 它关联了哪些单据（审批页回显用） */
    List<RelatedApproval> selectByBusinessId(@Param("businessId") String businessId);

    /** 反查：哪些宿主单据关联了这张单据（AC-55 的"按被关联单据反查"） */
    List<RelatedApproval> selectByRelatedBusinessId(@Param("relatedBusinessId") String relatedBusinessId);

    /**
     * 候选单据（服务端唯一口径）：模板命中候选清单、与宿主**同一分组**、且**本人发起**。
     *
     * @param allowTemplateIds 候选模板ID（调用方保证非空；为空时不该被调用）
     * @param hostType         宿主模板的分组（type）
     * @param userId           当前用户
     * @param keyword          单据号/标题关键字（可空）
     */
    List<RelatedApprovalCandidate> selectCandidates(@Param("allowTemplateIds") List<String> allowTemplateIds,
                                                    @Param("hostType") String hostType,
                                                    @Param("userId") String userId,
                                                    @Param("keyword") String keyword);

    /** 单张单据的存在性 + 单据号（落库前取快照，同时用于越界判定） */
    RelatedApprovalCandidate selectCandidateByBusinessId(@Param("businessId") String businessId);
}
