package com.ruoyi.ctms.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;

/**
 * <p> 往来单位档案（客户 / 供应商）的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsPartnerMapper.xml}。 </p>
 *
 * <p> 本包由全局 {@code @MapperScan("com.ruoyi.**.mapper")} 自动注册；
 * XML 文件名必须以 {@code Mapper.xml} 结尾，否则扫描不到（症状是
 * {@code Invalid bound statement (not found)}，见同目录 README）。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> 列表方法只按传入条件筛选：{@code enableFlag} 为 {@code null} 表示<b>不限</b>
 *        （档案管理页要看停用项），"只看启用"是服务层 {@code selectXxxOptions()} 的口径； </li>
 *   <li> 查重方法（{@code selectXxxByCode} / {@code selectXxxByName}）<b>不排除停用行</b>
 *        —— 编码与名称是全局唯一，停用项也占号； </li>
 *   <li> {@code updateXxx} <b>不改 {@code code}</b>（编码登记后不可变），其余业务列全量覆盖； </li>
 *   <li> {@code deleteXxxById} 是物理删除，引用保护在服务层判定，本层不做前置检查。 </li>
 * </ul>
 *
 * <p> ⚠ {@code countCustomerReferences} / {@code countSupplierReferences} 依赖合同表
 * {@code t_ctms_contract}，该表由本变更集第 4 组任务创建，<b>可能尚不存在</b>；
 * SQL 里直接写死表名在缺表时会报 {@code Table ... doesn't exist}。
 * 因此调用方必须先问 {@link #existsContractTable()}，只有返回 1 时才执行统计。 </p>
 *
 * @author 二开
 */
public interface CtmsPartnerMapper
{
    /* ==================== 客户档案 ==================== */

    /**
     * 客户档案列表（code/name 模糊、enableFlag 精确；enableFlag 为 null 表示不限）
     *
     * @param q 查询条件
     * @return 客户档案集合
     */
    List<CtmsCustomer> selectCustomerList(CtmsCustomer q);

    /**
     * 按主键查客户档案
     *
     * @param id 主键
     * @return 客户档案；不存在返回 null
     */
    CtmsCustomer selectCustomerById(@Param("id") String id);

    /**
     * 按编码查客户档案（查重用，含停用行）
     *
     * @param code 客户编码
     * @return 客户档案；不存在返回 null
     */
    CtmsCustomer selectCustomerByCode(@Param("code") String code);

    /**
     * 按名称查客户档案（查重用，含停用行）
     *
     * @param name 客户名称
     * @return 客户档案；不存在返回 null
     */
    CtmsCustomer selectCustomerByName(@Param("name") String name);

    /**
     * 新增客户档案
     *
     * @param c 客户档案
     * @return 影响行数
     */
    int insertCustomer(CtmsCustomer c);

    /**
     * 修改客户档案（不修改 code，其余业务列全量覆盖）
     *
     * @param c 客户档案
     * @return 影响行数
     */
    int updateCustomer(CtmsCustomer c);

    /**
     * 按主键物理删除客户档案（零引用放行与否由服务层判定）
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteCustomerById(@Param("id") String id);

    /**
     * 统计引用了该客户的合同数
     *
     * @param id 客户档案主键
     * @return 合同数
     */
    int countCustomerReferences(@Param("id") String id);

    /* ==================== 供应商档案 ==================== */

    /**
     * 供应商档案列表（code/name 模糊、enableFlag 精确；enableFlag 为 null 表示不限）
     *
     * @param q 查询条件
     * @return 供应商档案集合
     */
    List<CtmsSupplier> selectSupplierList(CtmsSupplier q);

    /**
     * 按主键查供应商档案
     *
     * @param id 主键
     * @return 供应商档案；不存在返回 null
     */
    CtmsSupplier selectSupplierById(@Param("id") String id);

    /**
     * 按编码查供应商档案（查重用，含停用行）
     *
     * @param code 供应商编码
     * @return 供应商档案；不存在返回 null
     */
    CtmsSupplier selectSupplierByCode(@Param("code") String code);

    /**
     * 按名称查供应商档案（查重用，含停用行）
     *
     * @param name 供应商名称
     * @return 供应商档案；不存在返回 null
     */
    CtmsSupplier selectSupplierByName(@Param("name") String name);

    /**
     * 新增供应商档案
     *
     * @param s 供应商档案
     * @return 影响行数
     */
    int insertSupplier(CtmsSupplier s);

    /**
     * 修改供应商档案（不修改 code，其余业务列全量覆盖）
     *
     * @param s 供应商档案
     * @return 影响行数
     */
    int updateSupplier(CtmsSupplier s);

    /**
     * 按主键物理删除供应商档案（零引用放行与否由服务层判定）
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteSupplierById(@Param("id") String id);

    /**
     * 统计引用了该供应商的合同数
     *
     * @param id 供应商档案主键
     * @return 合同数
     */
    int countSupplierReferences(@Param("id") String id);

    /* ==================== 合同表存在性 ==================== */

    /**
     * 合同表 {@code t_ctms_contract} 是否存在（1 = 存在，0 = 不存在）。
     *
     * <p> 引用统计的两条 SQL 都直接引用了合同表，缺表时会抛
     * {@code Table doesn't exist}，所以必须先用本方法探测；
     * 这一点在 B3 第 4 组建表之前尤其重要。 </p>
     *
     * @return 1 存在 / 0 不存在
     */
    int existsContractTable();
}
