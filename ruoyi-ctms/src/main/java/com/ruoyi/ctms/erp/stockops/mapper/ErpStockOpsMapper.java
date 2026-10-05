package com.ruoyi.ctms.erp.stockops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * <p> <b>调拨/盘点的只读辅助查询</b>（B4 任务 6.3 的"按商品类型含整棵子树"抽盘口径）。 </p>
 *
 * <p> 只读、只有 select：这里不写任何结存/流水的写入（那是过账引擎的唯一职责），
 * 也不改 B3 的物料/类型表结构。两条查询都只用 {@code #{}} 占位。 </p>
 *
 * <p> <b>为什么不复用 B3 的 {@code CtmsProductTypeMapper}</b>：B3 的那份是"类型档案 CRUD"，
 * 没有"取子树 id 集合"与"按类型集合取物料 id"这类盘点专用查询；写在这里可以避免给
 * B3 的档案 Mapper 加盘点专用方法（那会让两个变更集互相耦合）。 </p>
 *
 * @author 二开
 */
public interface ErpStockOpsMapper
{
    /**
     * 取某商品类型及其<b>整棵子树</b>的类型ID集合（含自身；递归 CTE）。
     *
     * @param typeId 商品类型ID
     * @return 类型ID集合（含自身；无则空集合）
     */
    List<String> selectTypeSubtreeIds(@Param("typeId") String typeId);

    /**
     * 按类型集合取<b>启用</b>物料ID（按编码排序，保证生成行项顺序稳定）。
     *
     * @param typeIds 类型ID集合（非空）
     * @return 物料ID集合（无则空集合）
     */
    List<String> selectEnabledProductIdsByTypeIds(@Param("typeIds") List<String> typeIds);
}
