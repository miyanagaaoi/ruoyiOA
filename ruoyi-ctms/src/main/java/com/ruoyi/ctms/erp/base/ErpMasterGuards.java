package com.ruoyi.ctms.erp.base;

import java.math.BigDecimal;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>行项主数据守卫与快照写入</b>（2.0 B4 任务 1.4；tasks.md §2.4/§5.1、AC-72）。 </p>
 *
 * <p> 三条规则（8 类单据的行项入口共用同一份实现）： </p>
 * <ol>
 *   <li> <b>停用物料/停用仓库/停用单位不可用于新行项</b>：引用已停用档案时抛业务异常，
 *        文案带<b>行号</b>（"行 2：物料「螺丝」已停用，不能用于新单据"），前端可直接定位； </li>
 *   <li> <b>快照写入</b>：物料编码/名称/规格、单位名/单位小数位在写入行项时固化；
 *        之后物料改名/停用/换单位都不影响历史单据（design D9 的"历史按快照展示"）； </li>
 *   <li> <b>数量精度按单位小数位</b>：{@code ErpAmounts.checkQuantityScale} 的执行者。 </li>
 * </ol>
 *
 * <p> <b>为什么不直接查 Mapper</b>：本类只依赖 {@link MasterLookup} 回调，
 * 于是规则可以脱离 Spring/数据库表驱动单测（{@code ErpMasterGuardsTest}）；
 * 生产实现 {@code ErpMasterLookupImpl} 才去调 B3 的三个档案 Mapper。 </p>
 *
 * <p> <b>本类不做</b>："是否存在结存/是否被单据引用"这类删除守卫（属主数据任务 2.x，
 * 在 {@code CtmsProductMasterServiceImpl} 里）。 </p>
 *
 * @author 二开
 */
public final class ErpMasterGuards
{
    /** 启用标志：启用（B3 DDL 的 {@code enable_flag} 口径）。 */
    public static final String ENABLE_YES = "1";

    /** 启用标志：停用。 */
    public static final String ENABLE_NO = "0";

    private ErpMasterGuards()
    {
    }

    /**
     * 主数据查询回调（物料 / 仓库 / 计量单位）。
     *
     * <p> 返回 {@code null} 表示"档案不存在"；实现方不应抛异常（异常统一由本类报，
     * 保证 8 类单据的错误文案一致）。 </p>
     */
    public interface MasterLookup
    {
        /**
         * @param productId 物料ID
         * @return 物料档案摘要；不存在返回 null
         */
        MasterRecord product(String productId);

        /**
         * @param warehouseId 仓库ID
         * @return 仓库档案摘要；不存在返回 null
         */
        MasterRecord warehouse(String warehouseId);

        /**
         * @param uomId 计量单位ID
         * @return 单位档案摘要；不存在返回 null
         */
        MasterRecord uom(String uomId);
    }

    /**
     * 主数据摘要（守卫与快照只需要这几个字段，避免把整个实体搬进公共层）。
     */
    public static class MasterRecord
    {
        private String id;

        private String code;

        private String name;

        private String spec;

        /** 物料的计量单位ID（仅物料有意义）。 */
        private String uomId;

        /** 单位小数位（仅单位有意义）。 */
        private Integer decimals;

        /** 启用标志：{@code '1'} 启用 / {@code '0'} 停用。 */
        private String enableFlag;

        public MasterRecord()
        {
        }

        public MasterRecord(String id, String code, String name)
        {
            this.id = id;
            this.code = code;
            this.name = name;
        }

        /**
         * 是否启用（{@code enable_flag} 非 {@code '0'} 视为启用，容忍历史空值）。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled()
        {
            return !ENABLE_NO.equals(enableFlag);
        }

        public String getId()
        {
            return id;
        }

        public void setId(String id)
        {
            this.id = id;
        }

        public String getCode()
        {
            return code;
        }

        public void setCode(String code)
        {
            this.code = code;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public String getSpec()
        {
            return spec;
        }

        public void setSpec(String spec)
        {
            this.spec = spec;
        }

        public String getUomId()
        {
            return uomId;
        }

        public void setUomId(String uomId)
        {
            this.uomId = uomId;
        }

        public Integer getDecimals()
        {
            return decimals;
        }

        public void setDecimals(Integer decimals)
        {
            this.decimals = decimals;
        }

        public String getEnableFlag()
        {
            return enableFlag;
        }

        public void setEnableFlag(String enableFlag)
        {
            this.enableFlag = enableFlag;
        }
    }

    /**
     * 校验并返回<b>启用</b>的物料档案。
     *
     * @param lookup    主数据查询
     * @param productId 物料ID
     * @param rowNo     行号（1 起；≤0 时文案不带行号）
     * @return 物料摘要
     * @throws ServiceException 物料ID为空 / 档案不存在 / 已停用
     */
    public static MasterRecord requireEnabledProduct(MasterLookup lookup, String productId, int rowNo)
    {
        return requireProduct(lookup, productId, rowNo, true);
    }

    /**
     * <p> 校验并返回物料档案（<b>可显式放过"已停用"</b>）。 </p>
     *
     * <p> {@code requireEnabled = false} 只放行"物料已停用"这一条，
     * 仍然拦"未选物料 / 档案不存在" —— 它是给<b>系统生成路径</b>用的窄口子
     * （见 {@link #applyGeneratedItemSnapshot}），<b>不是</b>全局开关：
     * 只有调用方显式传 {@code false} 才会放宽，默认与用户录入路径一样严格。 </p>
     *
     * @param lookup          主数据查询
     * @param productId       物料ID
     * @param rowNo           行号（1 起；≤0 时文案不带行号）
     * @param requireEnabled  是否要求物料启用（{@code true} = 用户录入路径）
     * @return 物料摘要
     * @throws ServiceException 物料ID为空 / 档案不存在 / （requireEnabled 时）已停用
     */
    private static MasterRecord requireProduct(MasterLookup lookup, String productId, int rowNo,
                                               boolean requireEnabled)
    {
        String prefix = rowPrefix(rowNo);
        String id = trimToNull(productId);
        if (id == null)
        {
            throw new ServiceException(prefix + "必须选择物料档案");
        }
        MasterRecord record = lookup == null ? null : lookup.product(id);
        if (record == null)
        {
            throw new ServiceException(prefix + "物料档案不存在：" + id);
        }
        if (requireEnabled && !record.isEnabled())
        {
            throw new ServiceException(prefix + "物料「" + safeName(record) + "」已停用，不能用于新单据");
        }
        return record;
    }

    /**
     * 校验并返回<b>启用</b>的仓库档案。
     *
     * @param lookup      主数据查询
     * @param warehouseId 仓库ID
     * @return 仓库摘要
     * @throws ServiceException 仓库ID为空 / 不存在 / 已停用
     */
    public static MasterRecord requireEnabledWarehouse(MasterLookup lookup, String warehouseId)
    {
        return requireEnabledWarehouse(lookup, warehouseId, null);
    }

    /**
     * 校验并返回启用的仓库档案（带上下文前缀，例如 {@code 行 2：}）。
     *
     * @param lookup      主数据查询
     * @param warehouseId 仓库ID
     * @param prefix      文案前缀（可为 null）
     * @return 仓库摘要
     * @throws ServiceException 未选 / 不存在 / 已停用
     */
    public static MasterRecord requireEnabledWarehouse(MasterLookup lookup, String warehouseId, String prefix)
    {
        String head = prefix == null ? "" : prefix;
        String id = trimToNull(warehouseId);
        if (id == null)
        {
            throw new ServiceException(head + "必须选择仓库");
        }
        MasterRecord record = lookup == null ? null : lookup.warehouse(id);
        if (record == null)
        {
            throw new ServiceException(head + "仓库不存在：" + id);
        }
        if (!record.isEnabled())
        {
            throw new ServiceException(head + "仓库「" + safeName(record) + "」已停用，不能用于新单据");
        }
        return record;
    }

    /**
     * 校验并返回<b>启用</b>的计量单位档案。
     *
     * @param lookup 主数据查询
     * @param uomId  单位ID
     * @return 单位摘要
     * @throws ServiceException 单位ID为空 / 不存在 / 已停用
     */
    public static MasterRecord requireEnabledUom(MasterLookup lookup, String uomId)
    {
        String id = trimToNull(uomId);
        if (id == null)
        {
            throw new ServiceException("物料未配置计量单位，无法用于新单据");
        }
        MasterRecord record = lookup == null ? null : lookup.uom(id);
        if (record == null)
        {
            throw new ServiceException("计量单位不存在：" + id);
        }
        if (!record.isEnabled())
        {
            throw new ServiceException("计量单位「" + safeName(record) + "」已停用，不能用于新单据");
        }
        return record;
    }

    /**
     * <p> <b>行项写入前的统一守卫 + 快照</b>（8 类单据只调这一个方法）： </p>
     * <ol>
     *   <li> 物料必须存在且启用； </li>
     *   <li> 写入物料编码/名称/规格快照； </li>
     *   <li> 物料的计量单位必须存在且启用，写入单位名与单位小数位快照； </li>
     *   <li> 数量的小数位不得超过单位小数位； </li>
     *   <li> 行金额按 {@code qty × unit_price} 先舍入到 2 位写回。 </li>
     * </ol>
     *
     * <p> <b>系统生成路径</b>（盘点差异生成的盘盈入库 / 盘亏出库单）请改用
     * {@link #applyGeneratedItemSnapshot}：它只豁免"物料已停用"，其余校验相同。 </p>
     *
     * @param lookup 主数据查询
     * @param item   行项（不得为空）
     * @param rowNo  行号（1 起；≤0 时文案不带行号）
     * @return 写入的快照（物料摘要 + 单位摘要），便于调用方再写历史/日志
     * @throws ServiceException 任一步校验失败
     */
    public static Snapshot applyItemSnapshot(MasterLookup lookup, ErpDocItem item, int rowNo)
    {
        return applySnapshot(lookup, item, rowNo, true);
    }

    /**
     * <p> <b>系统生成路径的行项守卫 + 快照（豁免"物料已停用"）</b>。 </p>
     *
     * <p> <b>为什么需要它</b>：盘点审核按差异自动生成的盘盈入库单 / 盘亏出库单，
     * 行项来自<b>该仓库已有的结存行</b> —— 这些物料可能在建立结存之后被停用。
     * 此时用面向用户录入路径的 {@link #applyItemSnapshot} 会报
     * "物料「X」已停用，不能用于新单据"，导致<b>盘点永远无法平账</b>
     * （AC-75 要求盘盈/盘亏必须过账成功）。停用是"不可再新录"的语义，
     * 它不应该阻断"把账实差异写回库存"这个系统动作。 </p>
     *
     * <p> <b>豁免面（刻意最小）</b>：只放过"物料已停用"这一条，
     * 其余校验与 {@link #applyItemSnapshot} <b>完全一致</b>并且仍然生效： </p>
     * <ul>
     *   <li> 未选物料 / 物料档案不存在 → 仍然拒绝（"物料档案不存在：ID"）； </li>
     *   <li> 物料的计量单位不存在 / 已停用 → 仍然拒绝； </li>
     *   <li> 数量精度（不得超过单位小数位）→ 仍然拒绝； </li>
     *   <li> 快照（编码/名称/规格/单位名/单位小数位）与行金额"先舍入到 2 位"照常写入。 </li>
     * </ul>
     *
     * <p> <b>不是全局开关</b>：没有系统参数、没有静态标志位，只有显式调用本方法才放宽
     * （与 {@code ErpPostingOptions.skipNegativeCheck()} 同一做法）。
     * 用户新建 / 编辑单据的行项一律走 {@link #applyItemSnapshot}，因此
     * "停用物料不可用于新行项"在用户路径上<b>不变</b>。 </p>
     *
     * @param lookup 主数据查询
     * @param item   行项（不得为空）
     * @param rowNo  行号（1 起；≤0 时文案不带行号）
     * @return 写入的快照（物料摘要 + 单位摘要）
     * @throws ServiceException 物料/单位不存在、单位已停用、数量精度超限
     */
    public static Snapshot applyGeneratedItemSnapshot(MasterLookup lookup, ErpDocItem item, int rowNo)
    {
        return applySnapshot(lookup, item, rowNo, false);
    }

    /**
     * 行项守卫 + 快照的公共实现（{@code requireEnabledProduct} 决定是否放过"物料已停用"）。
     *
     * @param lookup                主数据查询
     * @param item                  行项
     * @param rowNo                 行号
     * @param requireEnabledProduct 是否要求物料启用（用户路径 true / 生成路径 false）
     * @return 写入的快照
     */
    private static Snapshot applySnapshot(MasterLookup lookup, ErpDocItem item, int rowNo,
                                          boolean requireEnabledProduct)
    {
        if (item == null)
        {
            throw new ServiceException("行项不能为空");
        }
        MasterRecord product = requireProduct(lookup, item.getProductId(), rowNo, requireEnabledProduct);
        MasterRecord uom = requireEnabledUom(lookup, product.getUomId());
        item.applyProductSnapshot(product.getId(), product.getCode(), product.getName(),
                product.getSpec(), uom.getName(), uom.getDecimals());
        ErpAmounts.checkQuantityScale(item.getQty(), uom.getDecimals(), rowNo);
        item.recalcAmount();
        return new Snapshot(product, uom);
    }

    /**
     * <p> <b>行级仓库回落表头仓库</b>（tasks.md §5.1 的口径）。 </p>
     *
     * <p> 行项未填仓库时用表头仓库，两者都空时返回 {@code null} —— 由调用方在
     * <b>审核（过账前）</b>阶段报错并指明行号（"行 N：缺少仓库"），
     * 因为草稿允许暂缺（参考侧 {@code posting_service.py:64-66} 的分工）。 </p>
     *
     * @param item           行项
     * @param headerWarehouseId 表头仓库ID（可空）
     * @return 有效仓库ID；两者都空返回 null
     */
    public static String resolveWarehouseId(ErpDocItem item, String headerWarehouseId)
    {
        if (item != null)
        {
            String row = trimToNull(item.getWarehouseId());
            if (row != null)
            {
                return row;
            }
        }
        return trimToNull(headerWarehouseId);
    }

    /**
     * 行项写入时同步行级仓库快照（仓库名快照随行项落库，便于历史展示）。
     *
     * @param lookup  主数据查询
     * @param item    行项
     * @param rowNo   行号
     * @throws ServiceException 行项填了仓库但仓库不存在/已停用
     */
    public static void applyItemWarehouseSnapshot(MasterLookup lookup, ErpDocItem item, int rowNo)
    {
        if (item == null || trimToNull(item.getWarehouseId()) == null)
        {
            return;
        }
        MasterRecord warehouse = requireEnabledWarehouse(lookup, item.getWarehouseId(), rowPrefix(rowNo));
        item.setWarehouseId(warehouse.getId());
        item.setWarehouseName(warehouse.getName());
    }

    /**
     * 快照结果（调用方写变更历史/日志时可直接取名字）。
     */
    public static class Snapshot
    {
        private final MasterRecord product;

        private final MasterRecord uom;

        Snapshot(MasterRecord product, MasterRecord uom)
        {
            this.product = product;
            this.uom = uom;
        }

        public MasterRecord getProduct()
        {
            return product;
        }

        public MasterRecord getUom()
        {
            return uom;
        }
    }

    private static String rowPrefix(int rowNo)
    {
        return rowNo > 0 ? ("行 " + rowNo + "：") : "";
    }

    private static String safeName(MasterRecord record)
    {
        return record.getName() == null ? "" : record.getName();
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 金额入口的便捷转发（保证"只有一处乘法与舍入"这件事在行项写入路径上成立）。
     *
     * @param qty       数量
     * @param unitPrice 单价
     * @return 2 位行金额
     */
    public static BigDecimal lineAmount(BigDecimal qty, BigDecimal unitPrice)
    {
        return ErpAmounts.lineAmount(qty, unitPrice);
    }
}
