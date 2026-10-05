package com.ruoyi.ctms.erp.base;

/**
 * <p> <b>库存域系统参数</b>（2.0 B4 任务 1.3；Q-B8、tasks.md §5.3）。 </p>
 *
 * <p> 参数落在平台既有表 {@code sys_config}（不新建 KV 表，design D9 / 移植清单 §5），
 * 键名由本类冻结，取值解析也由本类提供 —— 过账服务（T3）只允许通过这里读，
 * 避免"参数名/取值语义"在两处漂移。 </p>
 *
 * <p> <b>默认关闭</b>：参数行缺失、值为空或值不可解析时一律按"不允许负库存"处理
 * （宁可拦住，也不要因为读不到配置而放开库存校验）。 </p>
 *
 * @author 二开
 */
public final class ErpStockParams
{
    /**
     * 系统参数键：是否允许负库存。
     *
     * <p> 参考侧参数名是 {@code allow_negative_stock}（`posting_service.py:63-70`）；
     * 目标侧加域前缀，避免与平台/其它域的参数撞名。 </p>
     */
    public static final String KEY_ALLOW_NEGATIVE_STOCK = "stock_allow_negative";

    /** 参数默认值（关闭）。 */
    public static final String DEFAULT_ALLOW_NEGATIVE_STOCK = "false";

    private ErpStockParams()
    {
    }

    /**
     * <p> 是否允许负库存。 </p>
     *
     * <p> 真值口径：{@code true} / {@code 1} / {@code Y} / {@code yes}（大小写不敏感、去空白）；
     * 其余（含 {@code null}、空串、{@code false}、无法识别的值）一律 false。 </p>
     *
     * @param configValue {@code sys_config.config_value} 的取值
     * @return 允许负库存返回 true
     */
    public static boolean negativeStockAllowed(String configValue)
    {
        return isTruthy(configValue);
    }

    /**
     * 真值判定（独立出来是因为"盘亏豁免"等分支也要用同一套判定）。
     *
     * @param configValue 配置值
     * @return 真值返回 true
     */
    public static boolean isTruthy(String configValue)
    {
        if (configValue == null)
        {
            return false;
        }
        String value = configValue.trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value)
                || "y".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }
}
