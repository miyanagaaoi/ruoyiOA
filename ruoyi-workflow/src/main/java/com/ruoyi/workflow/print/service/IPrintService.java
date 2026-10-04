package com.ruoyi.workflow.print.service;

import com.ruoyi.workflow.domain.PrintLog;
import com.ruoyi.workflow.domain.PrintTemplate;
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
}
