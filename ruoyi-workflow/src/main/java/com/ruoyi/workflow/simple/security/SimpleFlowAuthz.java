package com.ruoyi.workflow.simple.security;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.template.service.ITemplateService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <p> 简化流程设计器接口的<b>声明式</b>授权入口（2.0 B1 §3.4，REQ-PERM-005）。 </p>
 *
 * <p> 为什么需要它：delta spec 要求「被指定为流程管理员者 SHALL 能编辑并发布该模板的流程」，
 * 而平台 RBAC 里 {@code workflow:simpleFlow:*} 这几个权限点默认只有超管拥有
 * （{@code sys_menu} 有行，但没有角色被勾选）。如果继续用
 * {@code @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:publish')")} 卡住，
 * 被指定的流程管理员会被笼统的"没有访问权限"拒掉 —— 既不符合规格，
 * 也拿不到"为什么被拒"的明确提示。 </p>
 *
 * <p> 因此这里按<b>流程是否已绑定模板</b>分流： </p>
 * <ul>
 *   <li><b>已绑定模板</b>：这里放行，真正的判定交给服务层的模板级授权
 *       （{@code TemplateServiceImpl#checkFlowManagePermission}：系统管理员 / 被指定的流程管理员 /
 *       模板创建人 / 拥有 {@code workflow:template:edit} 者），越权时返回明确的
 *       「无流程管理权限」403；</li>
 *   <li><b>未绑定模板</b>（含 V1 存量流程、以及设计器里新建的独立流程）：沿用既有权限点，
 *       行为与升级前<b>完全一致</b>（零回归）。</li>
 * </ul>
 *
 * @author 二开
 */
@Component("flowAuthz")
public class SimpleFlowAuthz {

    @Autowired
    private ITemplateService templateService;

    /** 草稿保存（{@code /draft}）的声明式授权 */
    public boolean canEdit(String flowId) {
        return decide(flowId, "workflow:simpleFlow:edit");
    }

    /** 发布（{@code /publish}）的声明式授权 */
    public boolean canPublish(String flowId) {
        return decide(flowId, "workflow:simpleFlow:publish");
    }

    private boolean decide(String flowId, String permissionIfUnbound) {
        if (StringUtils.isNotBlank(flowId)) {
            String templateId = templateService.getTemplateIdBySimpleFlowId(flowId);
            if (StringUtils.isNotBlank(templateId)) {
                // 绑定模板的流程：放行到服务层做模板级判定（那里会给出明确的 403 原因）
                return true;
            }
        }
        return SecurityUtils.hasPermi(permissionIfUnbound);
    }
}
