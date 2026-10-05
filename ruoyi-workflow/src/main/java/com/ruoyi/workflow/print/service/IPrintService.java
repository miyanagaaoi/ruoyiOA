package com.ruoyi.workflow.print.service;

import com.ruoyi.workflow.domain.PrintLog;
import com.ruoyi.workflow.domain.PrintTemplate;
import com.ruoyi.workflow.print.model.BuiltinTemplateOption;
import com.ruoyi.workflow.print.model.PrintData;

import java.util.List;

/**
 * <p> 表单打印服务（PRD 第 7 章） </p>
 *
 * <p> 打印是<b>只读</b>动作：本服务不得修改任何业务数据（PRD 7.1 的不变量）。
 * 唯一的写操作是"打印留痕" {@link #writeLog(PrintLog)}。 </p>
 *
 * @author 二开
 */
public interface IPrintService {

    /**
     * 打印数据聚合。
     *
     * @param businessId 业务ID
     * @param printTplId 指定的打印模板ID，可为空（为空则取该单据模板下第一个启用的，或内置系统模板）
     */
    PrintData getPrintData(String businessId, String printTplId);

    /**
     * 取生效的打印模板。
     *
     * <p> <b>永远不返回 null</b>：DB 里没有配置时返回代码内置的系统模板，
     * 这样"还没配模板"的单据也能打印（PRD 7.7 的"本期=系统模板 + 字段映射"）。 </p>
     */
    PrintTemplate getEffectiveTemplate(String templateId, String printTplId);

    /** 某单据模板下的打印模板列表 */
    List<PrintTemplate> listTemplates(String templateId);

    /** 新增或更新打印模板 */
    PrintTemplate saveTemplate(PrintTemplate printTemplate);

    /** 逻辑删除打印模板 */
    int deleteTemplate(String id);

    /** 写打印留痕（只追加） */
    void writeLog(PrintLog printLog);

    /** 某单据的打印记录 */
    List<PrintLog> listLogs(String businessId);

    /* ==================== 内置版式（2.0 B2 / REQ-PRINT-011、REQ-PRINT-013） ==================== */

    /**
     * 内置版式清单（4 个 key + 展示名称）。
     *
     * <p> 客户端拿它渲染"选择内置模板"的下拉 —— 不让前端再抄一份名称常量。 </p>
     */
    List<BuiltinTemplateOption> listBuiltinTemplates();

    /**
     * 某个内置版式的字段映射（版式常量本身）。
     *
     * <p> 客户端拿它做配置页的"填入内置版式" —— 内置版式常量以后端为**唯一真源**。 </p>
     *
     * @param builtinKey 版式键；未登记的值回退 {@code contract}（不抛异常，记 warning）
     */
    Object getBuiltinFieldMap(String builtinKey);

    /**
     * 保存单据模板绑定的内置版式键（配置页"选择内置模板"模式）。
     *
     * <p> 只改这一列。非法 key **拒绝**并抛业务异常（与模板保存链路同一口径）。 </p>
     *
     * @return 影响行数（模板不存在或已删除时为 0）
     */
    int saveBuiltinPrintKey(String templateId, String builtinKey);
}
