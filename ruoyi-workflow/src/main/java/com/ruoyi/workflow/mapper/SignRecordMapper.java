package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.SignRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p> 签名记录 Mapper（表 t_workflow_sign_record） </p>
 *
 * <p> <b>刻意只提供 insert 与查询</b> —— 没有 update / delete：
 * 表是"只追加"的，撤签靠追加一条 signType=9 的记录，
 * 这样任何人（包括应用自己）都无法悄悄改掉一条已有的签名。 </p>
 *
 * @author 二开
 */
public interface SignRecordMapper {

    /** 新增（只追加） */
    int insert(SignRecord signRecord);

    /** 某单据的全部签名记录（按签名时间升序，便于取"每个节点的最新一条"） */
    List<SignRecord> selectByBusinessId(String businessId);

    /** 某单据的最后一条记录（取 prevHash 用；按签名时间倒序） */
    SignRecord selectLatestByBusinessId(String businessId);

    /** 某任务的最新一条有效签名（撤销后取不到，用于"是否已签"判定） */
    SignRecord selectLatestByTask(@Param("businessId") String businessId, @Param("taskId") String taskId);

    /**
     * 取业务ID对应的表单快照原文（表 {@code t_workflow_form}，主键即业务ID）。
     *
     * <p> 用于算 {@code formDataHash} —— 签名要能证明"签的是哪一版内容"（PRD 8.5）。
     * 走这里而不是 {@code IBizFormService}：后者要求先知道单据模板ID，
     * 而模板ID在流程变量与 procDefKey 里都没有（见 DEV-ENV §6 第 16 条），
     * 签名时再去反查一遍既绕又可能失败；表单快照本身用业务ID就能取到。
     * 取不到时返回 null，哈希链照常成立（只损失"内容版本"这一层证据）。 </p>
     */
    String selectFormDataById(String businessId);
}
