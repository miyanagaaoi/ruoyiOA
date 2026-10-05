package com.ruoyi.ctms.service;

import java.util.List;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;

/**
 * <p> 往来单位档案（客户 / 供应商）的业务接口（B3 §3，规格 ctms/business-partners）。 </p>
 *
 * <p> <b>两套列表口径，不要混用</b>： </p>
 * <ul>
 *   <li> {@code selectCustomerList(query)} —— 档案管理页用。<b>不</b>自动过滤停用项：
 *       {@code enableFlag} 传 {@code null} 就是"全部（含停用）"，这是刻意保留的，
 *       否则停用项在档案页会"消失不见、无法重新启用"； </li>
 *   <li> {@code selectCustomerOptions()} —— 合同表单的<b>选择器</b>用，只返回 {@code enable_flag='1'}。
 *       规格场景「停用后不可新选」就是靠这个口径落实的。 </li>
 * </ul>
 *
 * <p> 档案<b>不参与</b>合同台账的数据范围隔离（规格 ctms/business-partners
 * 「往来单位档案不做数据范围隔离」）：具备 {@code ctms:partner:query} 的用户能看到全部档案，
 * 不因创建人或部门不同而隐藏。 </p>
 *
 * <p> 档案只有启停用与"零引用时物理删除"，<b>没有</b>软删除；删除的引用保护在服务层判定，
 * 且停用<b>不</b>影响引用它的历史合同（无联动，这是规格要求的结果）。 </p>
 *
 * @author 二开
 */
public interface ICtmsPartnerService
{
    /* ==================== 客户档案 ==================== */

    /**
     * 查询客户档案列表（含停用项；{@code query.enableFlag} 为 null 表示不限）
     *
     * @param query 查询条件，可为 null
     * @return 客户档案集合
     */
    List<CtmsCustomer> selectCustomerList(CtmsCustomer query);

    /**
     * 查询启用中的客户档案（合同表单选择器专用）
     *
     * @return 只含 {@code enable_flag='1'} 的客户档案集合
     */
    List<CtmsCustomer> selectCustomerOptions();

    /**
     * 按主键查询客户档案
     *
     * @param id 主键
     * @return 客户档案；不存在返回 null
     */
    CtmsCustomer selectCustomerById(String id);

    /**
     * 新增客户档案（编码/名称查重、简称选填；服务端补 id 与审计列）
     *
     * @param c 客户档案
     */
    void insertCustomer(CtmsCustomer c);

    /**
     * 修改客户档案（编码不可改；改编码/名称时与<b>其他行</b>查重）
     *
     * @param c 客户档案
     */
    void updateCustomer(CtmsCustomer c);

    /**
     * 删除客户档案（仅零引用时物理删除，否则拒绝）
     *
     * @param id 主键
     */
    void deleteCustomerById(String id);

    /**
     * 启停用客户档案（只改 {@code enable_flag} 与审计列，无级联）
     *
     * @param id 主键
     * @param enableFlag 1-启用 0-停用
     * @return 影响行数
     */
    int changeCustomerStatus(String id, String enableFlag);

    /* ==================== 供应商档案 ==================== */

    /**
     * 查询供应商档案列表（含停用项；{@code query.enableFlag} 为 null 表示不限）
     *
     * @param query 查询条件，可为 null
     * @return 供应商档案集合
     */
    List<CtmsSupplier> selectSupplierList(CtmsSupplier query);

    /**
     * 查询启用中的供应商档案（合同表单选择器专用）
     *
     * @return 只含 {@code enable_flag='1'} 的供应商档案集合
     */
    List<CtmsSupplier> selectSupplierOptions();

    /**
     * 按主键查询供应商档案
     *
     * @param id 主键
     * @return 供应商档案；不存在返回 null
     */
    CtmsSupplier selectSupplierById(String id);

    /**
     * 新增供应商档案（编码/名称查重，简称必填，账期天数非负；服务端补 id 与审计列）
     *
     * @param s 供应商档案
     */
    void insertSupplier(CtmsSupplier s);

    /**
     * 修改供应商档案（编码不可改；简称必填，账期天数非负）
     *
     * @param s 供应商档案
     */
    void updateSupplier(CtmsSupplier s);

    /**
     * 删除供应商档案（仅零引用时物理删除，否则拒绝）
     *
     * @param id 主键
     */
    void deleteSupplierById(String id);

    /**
     * 启停用供应商档案（只改 {@code enable_flag} 与审计列，无级联）
     *
     * @param id 主键
     * @param enableFlag 1-启用 0-停用
     * @return 影响行数
     */
    int changeSupplierStatus(String id, String enableFlag);
}
