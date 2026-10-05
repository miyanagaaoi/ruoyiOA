package com.ruoyi.template.mapper;

import java.util.List;
import com.ruoyi.template.domain.TemplateMessageNotice;

/**
 * 消息通知模板Mapper接口
 * 
 * @author wocurr.com
 */
public interface TemplateMessageNoticeMapper {
    /**
     * 查询消息通知模板
     * 
     * @param id 消息通知模板主键
     * @return 消息通知模板
     */
    public TemplateMessageNotice selectTemplateMessageNoticeById(String id);

    /**
     * 查询消息通知模板列表
     * 
     * @param templateMessageNotice 消息通知模板
     * @return 消息通知模板集合
     */
    public List<TemplateMessageNotice> selectTemplateMessageNoticeList(TemplateMessageNotice templateMessageNotice);

    /**
     * 新增消息通知模板
     * 
     * @param templateMessageNotice 消息通知模板
     * @return 结果
     */
    public int insertTemplateMessageNotice(TemplateMessageNotice templateMessageNotice);

    /**
     * 修改消息通知模板
     * 
     * @param templateMessageNotice 消息通知模板
     * @return 结果
     */
    public int updateTemplateMessageNotice(TemplateMessageNotice templateMessageNotice);

    /**
     * 删除消息通知模板
     * 
     * @param id 消息通知模板主键
     * @return 结果
     */
    public int deleteTemplateMessageNoticeById(String id);

    /**
     * 批量删除消息通知模板
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteTemplateMessageNoticeByIds(String[] ids);

    /**
     * 根据模板id查询消息通知模板
     *
     * @param templateId
     * @return 结果
     */
    TemplateMessageNotice selectByTemplateId(String templateId);

    /**
     * 根据模板ID删除该模板的全部消息通知配置
     *
     * <p> 2.0（B1 §1.1）：模板改为原地更新后，同一 templateId 上只允许存在一套配置；
     * 保存前用它清掉旧行。 </p>
     *
     * @param templateId 模板ID
     * @return 结果
     */
    int deleteMessageNoticeByTemplateId(String templateId);
}
