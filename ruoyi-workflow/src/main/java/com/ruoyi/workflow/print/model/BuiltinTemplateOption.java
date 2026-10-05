package com.ruoyi.workflow.print.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <p> 内置打印版式的一个选项（{@code GET /workflow/print/builtinTemplates} 的响应元素） </p>
 *
 * <p> 只给"键 + 展示名称"：配置页需要它渲染"选择内置模板"的下拉，
 * 而版式内容（栏目）由 {@code GET /workflow/print/defaultFieldMap/{key}} 单独取。
 * 刻意做成接口而不是让前端再抄一份常量 —— 抄一份就又变成"前后端各一份"的重复。 </p>
 *
 * @author 二开
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuiltinTemplateOption implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 版式键：contract / fund / matter / payment */
    private String key;

    /** 展示名称（同时就是打印件标题） */
    private String name;

    /**
     * 该版式的**签批栏推荐取值**（{@code 0/1}）。
     *
     * <p> 配置页的实时预览要能显示"这套版式出不出签批栏"，
     * 所以把它随清单一起返回 —— 客户端不必再抄一份"哪套开签批栏"的规则。 </p>
     */
    private String showSignature;

    /** 该版式的**附件清单推荐取值**（{@code 0/1}） */
    private String showAttachment;
}
