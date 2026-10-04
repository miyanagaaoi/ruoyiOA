package com.ruoyi.workflow.sign.guard;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.mapper.AccessCheckMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <p> 「这个任务是不是我的」的服务端校验（PRD 11.3 越权防护 / AC-35） </p>
 *
 * <p> 签名接口原先只要求登录 —— 换个 {@code taskId} 就能替别人签，
 * 而签名记录要落 {@code sign_user_id} 并进哈希链，<b>替签比不签更糟</b>：
 * 打印件上会出现一个本人从未做过的确认。所以签名（与撤销）必须校验任务归属。 </p>
 *
 * <p> <b>为什么不给超级管理员开后门</b>：查看权可以放行（运维需要），签名不行 ——
 * 签名是"人证"，管理员替别人签下来的记录在审计上就是伪证。管理员要签，
 * 先把自己加签/转办成该任务的办理人。 </p>
 *
 * <p> <b>判定为什么不用 Flowable 的 {@code taskCandidateOrAssigned}</b>：
 * 实测它对"办理人正是当前用户"的在办任务也返回 null（把自己人挡在门外），
 * 见 {@link AccessCheckMapper} 的类注释。安全判定必须自己说了算。 </p>
 *
 * @author 二开
 */
@Slf4j
@Component
public class TaskOwnershipGuard {

    @Autowired
    private AccessCheckMapper accessCheckMapper;

    /**
     * 当前登录用户是否为该任务的办理人（含未签收的候选人）。
     *
     * @param taskId 任务ID
     */
    public boolean isMine(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return false;
        }
        String userId = SecurityUtils.getUserId();
        if (StringUtils.isBlank(userId)) {
            return false;
        }
        try {
            return accessCheckMapper.countTaskOwnerHit(taskId, userId) > 0;
        } catch (Exception e) {
            // 查询异常时**拒绝**：这是越权防护，失败放开等于没做
            log.error("签名：任务归属查询失败（按不属于处理）：taskId={} userId={}", taskId, userId, e);
            return false;
        }
    }

    /**
     * 不是本人任务时抛 403（AC-35：非本任务办理人调用签名接口返回 403）。
     *
     * @param taskId 任务ID
     */
    public void requireMine(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new ServiceException("签名缺少任务ID");
        }
        if (isMine(taskId)) {
            return;
        }
        log.warn("越权签名（已拒绝）：taskId={} userId={}", taskId, SecurityUtils.getUserId());
        throw new ServiceException("这个任务不属于你，无法签名", HttpStatus.FORBIDDEN);
    }
}
