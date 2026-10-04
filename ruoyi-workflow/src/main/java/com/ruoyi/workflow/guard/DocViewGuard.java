package com.ruoyi.workflow.guard;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.mapper.AccessCheckMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <p> 单据「查看权」的服务端校验（PRD 11.3 越权防护 / AC-35） </p>
 *
 * <p> 打印是查看权的延伸（PRD 7.6「对单据有查看权即可打印」）——
 * 那么"有查看权"这件事就必须由服务端判，不能因为前端只画了入口就当作没这回事：
 * 换个 {@code businessId} 直接调 {@code /workflow/print/data/{businessId}}
 * 就能把别人的合同连金额带签批意见全打印出来。 </p>
 *
 * <p> 判定口径见 {@link AccessCheckMapper}：待办 / 已办 / 回收站 / 发起人，四路任一命中即放行。 </p>
 *
 * <p> <b>超级管理员放行</b>：运维排查、代打、模板配置页预览都需要跨单据可见；
 * 这与 RuoYi 一贯的 admin 语义一致。注意签名接口**不做**这个放行
 * （见 {@code TaskOwnershipGuard}：签名是"人证"，管理员也不能替别人签）。 </p>
 *
 * @author 二开
 */
@Slf4j
@Component
public class DocViewGuard {

    @Autowired
    private AccessCheckMapper accessCheckMapper;

    /**
     * 当前登录用户对该单据是否有查看权。
     *
     * @param businessId 业务ID
     */
    public boolean canView(String businessId) {
        if (StringUtils.isBlank(businessId)) {
            return false;
        }
        String userId = SecurityUtils.getUserId();
        if (SecurityUtils.isAdmin(userId)) {
            return true;
        }
        try {
            return accessCheckMapper.countViewerHit(businessId, userId) > 0;
        } catch (Exception e) {
            // 查询失败时必须**拒绝**而不是放行：这里是越权防护，失败放开等于没做
            log.error("单据查看权校验失败（按无权处理）：businessId={} userId={}", businessId, userId, e);
            return false;
        }
    }

    /**
     * 无查看权时抛 403（AC-35：无查看权的用户调用打印数据接口返回 403）。
     *
     * @param businessId 业务ID
     */
    public void requireViewable(String businessId) {
        if (StringUtils.isBlank(businessId)) {
            throw new ServiceException("缺少业务ID");
        }
        if (canView(businessId)) {
            return;
        }
        log.warn("越权访问单据（已拒绝）：businessId={} userId={}", businessId, SecurityUtils.getUserId());
        throw new ServiceException("没有查看权，无法查看或打印该单据", HttpStatus.FORBIDDEN);
    }
}
