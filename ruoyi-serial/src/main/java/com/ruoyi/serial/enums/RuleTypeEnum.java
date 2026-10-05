package com.ruoyi.serial.enums;

/**
 * 编号规则枚举
 * @Author wocurr.com
 */
public enum RuleTypeEnum {
    FIXED("0", "固定值"),
    DATE("1", "日期"),
    SEQ("2", "流水号"),
    /**
     * 业务参数（2.0 B3 §1.3 新增）：{@code ruleValue} 是<b>参数名</b>，
     * 取值由调用方通过 {@code CodeGenContext.params} 传入。
     *
     * <p> 为什么需要它：合同编号的「类型码」「主体码」是业务取值，不能写死在配置里
     * （否则 N 个类型 × M 个主体要建 N×M 条配置）。
     * 传了业务参数还会触发<b>分桶计数</b>（见 {@code CodeRenderSupport.bucketKeyOf}）。 </p>
     *
     * <p> ⚠ 新增枚举值不影响既有配置：老配置只用 0/1/2。 </p>
     */
    PARAM("3", "业务参数"),
    ;
    private final String code;
    private final String desc;

    RuleTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    public String getCode() {
        return code;
    }
    public String getDesc() {
        return desc;
    }

    public static RuleTypeEnum getByCode(String code) {
        for (RuleTypeEnum item : RuleTypeEnum.values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }

}
