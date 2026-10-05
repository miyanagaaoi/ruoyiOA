package com.ruoyi.ctms.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.ctms.domain.CtmsContractItem;

/**
 * <p> 合同行项的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsContractItemMapper.xml}。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> 行项是合同的从属明细：编辑合同时的口径是「先按合同全删、再整批插入」，
 *        因此提供 {@link #deleteItemsByContractId} + {@link #batchInsertItems} 这一对； </li>
 *   <li> {@link #selectItemsByContractIds} 供列表页一次性取回多合同的行项摘要
 *        （避免逐条查询）；空集合由服务层挡在调用之前； </li>
 *   <li> {@link #countByProductId} 是物料档案的引用守卫：被行项引用的物料不允许删除。 </li>
 * </ul>
 *
 * <p> 行总价字段 {@code total} 由服务层用
 * {@code com.ruoyi.ctms.support.ContractRules#lineTotal} 先舍入再写入，本层不做计算。 </p>
 *
 * @author 二开
 */
public interface CtmsContractItemMapper
{
    /**
     * 行项列表（contractId / contractIds / productId / itemType 与名称模糊，传了才过滤）
     *
     * @param query 查询条件
     * @return 行项集合（按合同、序号升序）
     */
    List<CtmsContractItem> selectItemList(CtmsContractItem query);

    /**
     * 按合同ID集合批量查行项（列表页取摘要用）
     *
     * @param contractIds 合同ID集合（服务层保证非空）
     * @return 行项集合（按合同、序号升序）
     */
    List<CtmsContractItem> selectItemsByContractIds(@Param("contractIds") List<String> contractIds);

    /**
     * <p> 按主键查行项（任务 7.4 追加的行项写端点用）。 </p>
     *
     * <p> 用途是「先按行项 id 反查它属于哪份合同」，再拿着合同 id 走合同侧唯一的数据范围入口
     * （{@code ICtmsContractService} 的 {@code requireAccessible}）——行项自己没有范围列，
     * 绝不能在这里另写一份权限判定。 </p>
     *
     * @param id 行项主键
     * @return 行项；不存在返回 null
     */
    CtmsContractItem selectItemById(@Param("id") String id);

    /**
     * 按合同ID删除全部行项（编辑时的「先全删」半步）
     *
     * @param contractId 合同ID
     * @return 影响行数
     */
    int deleteItemsByContractId(@Param("contractId") String contractId);

    /**
     * 批量插入行项（id 由服务层生成；序号由服务层按提交顺序重排）
     *
     * @param items 行项集合（服务层保证非空）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<CtmsContractItem> items);

    /**
     * 统计引用了该物料档案的行项数量（删除物料前的守卫）
     *
     * @param productId 物料档案ID
     * @return 行项数量
     */
    int countByProductId(@Param("productId") String productId);
}
