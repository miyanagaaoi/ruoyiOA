package com.ruoyi.ctms.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.domain.CtmsAttachment;

/**
 * <p> 附件挂载元数据的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsAttachmentMapper.xml}。 </p>
 *
 * <p> 本表<b>只软删除</b>：没有物理 delete 方法，删除改 {@code del_flag='1'}，
 * 因此列表与按 id 取单条都按 {@code del_flag='0'} 过滤（任务 6.3 的
 * "附件元数据删除后下载接口不再返回该文件"由此保证）。 </p>
 *
 * <p> <b>数据范围</b>：附件继承它所属合同的数据范围。列表查询通过
 * {@code contractDataScopeSql} 参数（服务端白名单片段，{@code ${}} 原样拼接）
 * 对 {@code contract_id} 做一次 EXISTS 判定，判定逻辑与合同列表<b>同一处</b>
 * （{@code ContractDataScope} + {@code CtmsContractServiceImpl.canAccess}），
 * 不在本层重复实现。 </p>
 *
 * @author 二开
 */
public interface CtmsAttachmentMapper
{
    /**
     * 附件列表（按对象定位；只返回未删除行）。
     *
     * <p> 数据范围片段可空：为 null/空时不加条件（调用方已在服务层做过对象级校验的场景）。 </p>
     *
     * @param objectType          对象类型（必传）
     * @param objectId            对象标识（必传）
     * @param contractDataScopeSql 服务端白名单拼出的数据范围片段（可空）
     * @return 附件集合（按上传时间倒序）
     */
    List<CtmsAttachment> selectAttachmentList(@Param("objectType") String objectType,
                                             @Param("objectId") String objectId,
                                             @Param("contractDataScopeSql") String contractDataScopeSql);

    /**
     * 按主键取单条（只返回未删除行；删除后取不到是刻意行为）。
     *
     * @param id 附件ID
     * @return 附件；不存在或已删除返回 null
     */
    CtmsAttachment selectAttachmentById(@Param("id") String id);

    /**
     * 落一条附件元数据（id 由服务层生成，create_time/update_time 走 {@code sysdate()}）。
     *
     * @param attachment 附件
     * @return 影响行数
     */
    int insertAttachment(CtmsAttachment attachment);

    /**
     * 软删除（置 {@code del_flag='1'} 并写更新人/时间）。
     *
     * @param id       附件ID
     * @param updateId 操作人用户ID
     * @param updateBy 操作人登录名快照
     * @return 影响行数
     */
    int softDeleteAttachment(@Param("id") String id,
                             @Param("updateId") String updateId,
                             @Param("updateBy") String updateBy);

    /**
     * 统计某对象下未删除的附件数量（文档/核对用）。
     *
     * @param objectType 对象类型
     * @param objectId   对象标识
     * @return 数量
     */
    int countByObject(@Param("objectType") String objectType, @Param("objectId") String objectId);
}
