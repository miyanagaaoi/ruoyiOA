package com.ruoyi.ctms.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.ctms.domain.CtmsPartyDraft;
import com.ruoyi.ctms.domain.vo.CtmsMigrationClaimVo;
import com.ruoyi.ctms.service.ICtmsMigrationService;

/**
 * <p> 迁移认领控制器（2.0 B3 任务 7.1 起；design.md 的「数据收敛」第 1 步）。 </p>
 *
 * <p> <b>权限点不新增</b>：本类只用菜单 SQL
 * （{@code sql/二开-合同台账-菜单.sql}，第 7 组相关的三个按钮权限点已建）里<b>已存在</b>的
 * {@code ctms:migration:scan}（扫描）与 {@code ctms:migration:list}（草案列表）——
 * 9.1 的权限点双向核对要求 java 侧集合与菜单 SQL 集合完全相等，
 * 在这里"顺手造一个新权限点"会当场把那条门禁弄红。 </p>
 *
 * <p> <b>路由形态</b>：两个端点都是无路径变量的单段字面量（{@code /scan}、{@code /drafts}），
 * 与合同控制器里 {@code /next-no} 的处理一致 —— 从根上避免与未来可能出现的 {@code /{id}} 竞争。 </p>
 *
 * <p> <b>扫描的返回形态</b>：{@code data} = 本次实际新建或刷新的草案集合，另附 {@code count} 计数；
 * 幂等的含义是"数据没变时第二次扫描 {@code count=0}"，而不是"草案总数"（总数可由
 * {@code GET /ctms/migration/drafts} 查）。扫描会记一条平台操作日志（{@code @Log}）：
 * 它是会写库的运维动作，必须留下"谁在什么时候点了扫描"。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/ctms/migration")
public class CtmsMigrationController extends BaseController
{
    @Autowired
    private ICtmsMigrationService migrationService;

    /**
     * <p> 扫描历史合同的甲乙方文本，生成/刷新待认领草案（任务 7.1）。 </p>
     *
     * <p> 服务层全程只读合同表；重复扫描不重复建草案（幂等键 {@code uk_party_draft} + 先查后写）。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:migration:scan')")
    @Log(title = "迁移扫描", businessType = BusinessType.OTHER)
    @PostMapping("/scan")
    public AjaxResult scan()
    {
        List<CtmsPartyDraft> touched = migrationService.scanPartyDrafts();
        AjaxResult ajax = AjaxResult.success(touched);
        ajax.put("count", touched.size());
        return ajax;
    }

    /**
     * 草案列表（方向 / 原始名称（模糊）/ 状态 / 已匹配档案；{@code status=all} 表示全部状态）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:migration:list')")
    @GetMapping("/drafts")
    public TableDataInfo drafts(CtmsPartyDraft query)
    {
        startPage();
        List<CtmsPartyDraft> list = migrationService.selectDraftList(query);
        return getDataTable(list);
    }

    /**
     * <p> 认领一条草案并按原始名称批量绑定历史合同（权限点 {@code ctms:migration:claim}）。 </p>
     *
     * <p> 请求体两种形态：带 {@code partyId} = 绑定已有档案；不带 = 按请求体字段新建档案后绑定
     * （供应商简称缺省时以草案 {@code raw_name} 兜底）。响应体是认领后的草案
     * （{@code status=claimed} + {@code matchedId} + 本次实际绑定的 {@code contractCount}）。 </p>
     *
     * <p> 幂等语义由状态机给：已认领的草案再次提交会被拒（提示含「已认领」），
     * 已忽略的草案允许重新认领。 </p>
     *
     * @param id      草案主键
     * @param request 认领请求（可为空 body）
     */
    @PreAuthorize("@ss.hasPermi('ctms:migration:claim')")
    @Log(title = "迁移认领", businessType = BusinessType.UPDATE)
    @PostMapping("/drafts/{id:[A-Za-z0-9]+}/claim")
    public AjaxResult claim(@PathVariable("id") String id,
                            @RequestBody(required = false) CtmsMigrationClaimVo request)
    {
        return success(migrationService.claimPartyDraft(id, request));
    }

    /**
     * <p> 忽略一条草案（权限点 {@code ctms:migration:ignore}）。 </p>
     *
     * <p> 已忽略的草案重复忽略是幂等的；已认领的草案不允许忽略
     * （它已经把档案引用写进合同，"忽略"会让状态与事实矛盾）。被忽略的草案不会再被扫描复活。 </p>
     *
     * @param id     草案主键
     * @param remark 忽略原因（可空，写入草案 {@code remark}）
     */
    @PreAuthorize("@ss.hasPermi('ctms:migration:ignore')")
    @Log(title = "迁移忽略", businessType = BusinessType.UPDATE)
    @PostMapping("/drafts/{id:[A-Za-z0-9]+}/ignore")
    public AjaxResult ignore(@PathVariable("id") String id,
                             @RequestParam(value = "remark", required = false) String remark)
    {
        return success(migrationService.ignorePartyDraft(id, remark));
    }
}
