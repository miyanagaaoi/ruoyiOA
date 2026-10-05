package com.ruoyi.ctms.erp.base.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * <p> 单据"按标识取访问判定所需字段"的公共查询（2.0 B4 任务 1.4 的附件接入点）。 </p>
 *
 * <p> <b>为什么需要它</b>：附件（{@code t_ctms_attachment}）是<b>多态挂载</b>的
 * （{@code object_type} + {@code object_id}），B4 的 6 类单据分散在 6 张表里；
 * 若每类单据各自实现一遍"对象存在 + 数据范围"，就必然漂移。这里用<b>一条</b>
 * 按单据类型分支的查询把它们收口，附件服务与单据服务共用同一口径。 </p>
 *
 * <p> <b>为什么用 {@code <choose>} 而不是动态表名</b>：表名只允许来自
 * {@code ErpDocType} 枚举（服务端冻结值），但把表名拼进 SQL 仍然等于留了一个
 * 注入面。用 8 个显式的 {@code when} 分支，SQL 里出现的表名全部是字面量，
 * 任何请求参数都只能落到 {@code #{}} 占位上。 </p>
 *
 * <p> 返回 {@code Map} 而不是实体：调用方只要 5 个字段（id/doc_no/status/dept_id/create_id），
 * 为此引入 8 个新实体类不值得。列名一律在 SQL 里别名成驼峰，
 * 与 {@code map-underscore-to-camel-case} 的开/关无关。 </p>
 *
 * @author 二开
 */
public interface ErpDocLookupMapper
{
    /**
     * 按单据类型与标识取一行未删除单据的表头判定字段。
     *
     * @param docType 单据类型（{@code ErpDocType.getCode()}；未知类型返回 null）
     * @param docId   单据ID
     * @return 键为 {@code id}/{@code docNo}/{@code status}/{@code deptId}/{@code createId} 的 Map；
     *         不存在或类型未知返回 null
     */
    Map<String, Object> selectDocHeaderByType(@Param("docType") String docType, @Param("docId") String docId);

    /**
     * 部门祖先链（数据范围"含下级"判定用，复用平台 {@code sys_dept.ancestors}）。
     *
     * @param deptId 部门ID
     * @return 形如 {@code "0,100,101"} 的祖先链；查不到返回 null
     */
    String selectDeptAncestors(@Param("deptId") String deptId);
}
