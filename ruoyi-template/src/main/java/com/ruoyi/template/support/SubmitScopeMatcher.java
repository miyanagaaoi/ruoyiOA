package com.ruoyi.template.support;

import com.ruoyi.template.domain.TemplateSubmitScope;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「谁可以提交该审批」的判定核心（2.0 B1 §3.2，REQ-PERM-001）。
 *
 * <p> <b>为什么单独抽一个纯函数类</b>：这段判定既要在发起页列表里批量跑（每个模板一次），
 * 又要在发起落库前再跑一次兜底，还要能被单元测试覆盖六种情形
 * （全员可见 / 角色命中 / 部门命中 / 部门下级命中 / 关掉下级后不命中 / 管理员全可见）。
 * 依赖 {@code SecurityUtils} 的写法没法单测，故把"人"的上下文做成 {@link ScopeUser} 传进来。 </p>
 *
 * <p> 语义（对齐 delta spec {@code workflow/submit-scope}）：
 * <ul>
 *   <li>系统管理员不受范围限制；</li>
 *   <li>{@code scopeType} 为空或 {@code 0} = 全员；</li>
 *   <li>明细里<b>任一条命中</b>即可见（多态：按每条自己的 {@code scopeType} 判定）；</li>
 *   <li>部门范围默认含下级：用户部门的物化路径 {@code ancestors} 里出现该部门即命中；</li>
 *   <li>类型是"指定"类但明细为空 → 除管理员外无人可见（配置不完整时宁可不放行）。</li>
 * </ul> </p>
 *
 * @author 二开
 */
public final class SubmitScopeMatcher {

    /** 全员 */
    public static final String TYPE_ALL = "0";
    /** 指定人员 */
    public static final String TYPE_USER = "1";
    /** 指定角色 */
    public static final String TYPE_ROLE = "2";
    /** 指定部门 */
    public static final String TYPE_DEPT = "3";

    private SubmitScopeMatcher() {
    }

    /**
     * 判定发起人上下文：部门物化路径与角色集合都由调用方查好传进来（便于单测与批量化）。
     */
    public static final class ScopeUser {
        private final String userId;
        private final String deptId;
        private final Set<String> roleIds;
        /** 用户所属部门的物化路径，形如 {@code 0,100,101}（可为空） */
        private final String deptAncestors;
        private final boolean admin;

        public ScopeUser(String userId, String deptId, Set<String> roleIds, String deptAncestors, boolean admin) {
            this.userId = userId;
            this.deptId = deptId;
            this.roleIds = roleIds == null ? Collections.<String>emptySet() : roleIds;
            this.deptAncestors = deptAncestors;
            this.admin = admin;
        }

        public String getUserId() {
            return userId;
        }

        public String getDeptId() {
            return deptId;
        }

        public Set<String> getRoleIds() {
            return roleIds;
        }

        public String getDeptAncestors() {
            return deptAncestors;
        }

        public boolean isAdmin() {
            return admin;
        }
    }

    /**
     * 能否发起该模板。
     *
     * @param scopeType        模板上的范围类型（0-全员 1-人员 2-角色 3-部门）
     * @param includeChildDept 部门范围是否含下级（"1" 表示含；null/其他视为不含）
     * @param details          范围明细（可为空集合）
     * @param user             发起人上下文
     */
    public static boolean canStart(String scopeType, String includeChildDept,
                                   List<TemplateSubmitScope> details, ScopeUser user) {
        if (user == null) {
            return false;
        }
        // 系统管理员不受范围限制
        if (user.isAdmin()) {
            return true;
        }
        // 未配置 = 全员（存量模板全部走这条，保证升级前行为不变）
        if (StringUtils.isBlank(scopeType) || TYPE_ALL.equals(scopeType)) {
            return true;
        }
        if (CollectionUtils.isEmpty(details)) {
            // "指定"类但没配明细：配置不完整，不放行（保存时前端也会拦）
            return false;
        }
        boolean withChildren = "1".equals(includeChildDept);
        for (TemplateSubmitScope detail : details) {
            if (detail == null || StringUtils.isBlank(detail.getTargetId())) {
                continue;
            }
            String type = detail.getScopeType();
            String target = detail.getTargetId();
            if (TYPE_USER.equals(type) && StringUtils.equals(user.getUserId(), target)) {
                return true;
            }
            if (TYPE_ROLE.equals(type) && user.getRoleIds().contains(target)) {
                return true;
            }
            if (TYPE_DEPT.equals(type)) {
                if (StringUtils.equals(user.getDeptId(), target)) {
                    return true;
                }
                // 含下级：目标部门出现在我的祖先路径里，即"我属于它的后代部门"
                if (withChildren && isDescendantOf(user.getDeptAncestors(), target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 目标部门是否是"我的祖先"。
     *
     * <p> 用逗号切分后做**整段相等**比较，不能用 {@code contains} 子串匹配 ——
     * 否则部门 {@code 100} 会被 {@code 1000} 误命中。 </p>
     */
    private static boolean isDescendantOf(String ancestors, String targetDeptId) {
        if (StringUtils.isBlank(ancestors) || StringUtils.isBlank(targetDeptId)) {
            return false;
        }
        Set<String> parts = new HashSet<String>(Arrays.asList(ancestors.split(",")));
        return parts.contains(targetDeptId);
    }
}
