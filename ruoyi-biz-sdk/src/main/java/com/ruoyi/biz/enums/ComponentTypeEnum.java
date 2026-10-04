package com.ruoyi.biz.enums;

/**
 * <p> 表单字段组件枚举类 </p>
 *
 * @Author wocurr.com
 */
public enum ComponentTypeEnum {
    EL_SELECT("el-select"),
    EL_CASCADER("el-cascader"),
    EL_RADIO_GROUP("el-radio-group"),
    EL_CHECKBOX_GROUP("el-checkbox-group"),
    EL_DATE_PICKER("el-date-picker"),
    EL_TIME_PICKER("el-time-picker"),
    ORG_SELECT("design-dept-select"),
    USER_SELECT("design-user-select"),
    /** 金额（PRD 9.3）：值是数字，千分位与大写由前端内置计算生成 */
    AMOUNT("design-amount"),
    /** 只读计算（PRD 9.3）：如"日期区间 → 天数" */
    CALC("design-calc"),
    /** 表单内嵌签名位（P1）：值 = fileId */
    SIGNATURE("design-signature"),
    /** 分组标题：纯排版，**没有值**，永远不会出现在 valData 里 */
    SECTION("design-section"),
    /** 说明文字：纯排版，**没有值** */
    TEXT("design-text"),
    UNKNOWN("unknown");

    private final String tag;

    ComponentTypeEnum(String tag) {
        this.tag = tag;
    }

    public static ComponentTypeEnum fromTag(String tag) {
        for (ComponentTypeEnum type : values()) {
            if (type.tag.equals(tag)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    public String getTag() {
        return tag;
    }
}
