package com.ruoyi.ctms.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsContractItem;
import com.ruoyi.ctms.service.ICtmsContractService;

/**
 * <p> 合同台账控制器（2.0 B3 任务 4.1~4.8）。 </p>
 *
 * <p> 权限点真源是 {@code sql/二开-合同台账-菜单.sql}，本类里的
 * {@code ctms:contract:list/query/add/edit/remove/status/export} 与
 * {@code ctms:contract-item:list} 与之<b>逐字一致</b>。 </p>
 *
 * <p> <b>路由冲突的显式处理</b>：所有路径变量都收窄为 {@code [A-Za-z0-9]+}
 * （与既有 {@code CtmsPartnerController} 同款），
 * 目的是让 {@code /list}、{@code /export}、{@code /status}、{@code /framework/...}、
 * {@code /items/...} 这些字面量段与 {@code {id}} 候选在映射层面就区分开：
 * 本仓库的标识是 32 位十六进制 UUID，不含连字符（{@code change-logs} 因此必然是字面量）。
 * 第 5 组新增的两个端点 {@code /next-no} 与 {@code /warranty-reminders} 刻意是<b>无路径变量</b>的
 * 单段字面量路由，从根上避免与 {@code /{id}} 竞争。 </p>
 *
 * <p> <b>数据范围 403 的返回形态</b>：范围外由服务层抛
 * {@code ServiceException("无权访问该合同", HttpStatus.FORBIDDEN)}，
 * {@code ruoyi-framework} 的 {@code GlobalExceptionHandler.handleServiceException}
 * 对带 code 的 ServiceException 返回 {@code AjaxResult.error(code, msg)}，
 * 即响应体 {@code {"code":403,"msg":"无权访问该合同"}}（HTTP 仍为 200）。
 * 这与既有 {@code DocViewGuard} 一致，也正好是 {@code tools/authz-check.ps1}
 * 的 {@code IsForbidden} 所断言的形态（按响应体业务码判定），
 * 所以这里<b>不</b>需要 try/catch 改写返回体 —— 改写反而会让其它业务异常的形态分化。
 * 导出接口同样经过服务层的同一判定。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/ctms/contract")
public class CtmsContractController extends BaseController
{
    @Autowired
    private ICtmsContractService contractService;

    /**
     * 行项单行写的窄接口（同一 Spring bean；接口隔离见 {@code ICtmsContractService.ItemWriter} 的注释）。
     */
    @Autowired
    private ICtmsContractService.ItemWriter contractItemWriter;

    /**
     * 查询合同列表（关键字 / 类型 / 状态 / 到货状态 / 框架 / 经办人 / 签订日期区间 /
     * 标签交集 / 是否含停用全部由 Mapper 完成；数据范围由服务层强制）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:list')")
    @GetMapping("/list")
    public TableDataInfo list(CtmsContract query)
    {
        startPage();
        List<CtmsContract> list = contractService.selectContractList(query);
        return getDataTable(list);
    }

    /**
     * <p> 导出合同台账（任务 7.4：从"只返回分页数据"补成<b>真实 Excel</b>）。 </p>
     *
     * <p> 三条口径： </p>
     * <ol>
     *   <li> 取数<b>只走</b> {@link ICtmsContractService#selectContractList}：与列表同一处数据范围判定
     *        （{@code ContractDataScope} 拼的白名单片段），不存在"列表看不到、导出全拿到"的越权口子； </li>
     *   <li> 列定义来自 {@link ContractExportRow} 的 {@code @Excel} 注解（{@code ExcelUtil} 按注解取列，
     *        所以用一个显式的导出行对象，而不是给实体挂表现层注解）； </li>
     *   <li> 甲方/乙方在导出行里就是文本字段（未绑定档案的历史合同照样原样输出文本，不因缺档案引用而空/报错）。 </li>
     * </ol>
     *
     * <p> 行装配抽成包内可见的 {@link #exportRows(CtmsContract)}，单测可直接核对行内容与甲乙列，
     * 不必伪造 HTTP 响应体。 </p>
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:export')")
    @Log(title = "合同台账", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, CtmsContract query)
    {
        ExcelUtil<ContractExportRow> util = new ExcelUtil<>(ContractExportRow.class);
        util.exportExcel(response, exportRows(query), "合同台账");
    }

    /**
     * 装配导出行（与列表同一处数据范围判定；{@code public} 以便单测直接核对，不额外暴露接口）。
     *
     * @param query 筛选条件（可为 null）
     * @return 导出行集合（顺序与列表一致）
     */
    public List<ContractExportRow> exportRows(CtmsContract query)
    {
        List<CtmsContract> list = contractService.selectContractList(query);
        List<ContractExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (CtmsContract contract : list)
            {
                if (contract != null)
                {
                    rows.add(ContractExportRow.of(contract));
                }
            }
        }
        return rows;
    }

    /**
     * <p> 导出用行对象：{@code ExcelUtil} 的列来自 {@code @Excel} 注解，所以把"导出列"显式写在
     * 这个行对象上，而不是给 {@code CtmsContract} 实体挂注解（实体同时是接口的 JSON 契约）。 </p>
     *
     * <p> 甲/乙方是文本字段：未绑定档案的历史合同（迁移期）照样输出文本值。 </p>
     */
    public static class ContractExportRow
    {
        /** 合同编号 */
        @Excel(name = "合同编号")
        private String contractNo;

        /** 合同名称 */
        @Excel(name = "合同名称")
        private String name;

        /** 合同类型（类型码） */
        @Excel(name = "合同类型")
        private String type;

        /** 甲方（文本） */
        @Excel(name = "甲方")
        private String partyA;

        /** 乙方（文本） */
        @Excel(name = "乙方")
        private String partyB;

        /** 签订日期 */
        @Excel(name = "签订日期", dateFormat = "yyyy-MM-dd")
        private Date signDate;

        /** 合同金额 */
        @Excel(name = "合同金额", cellType = Excel.ColumnType.NUMERIC)
        private BigDecimal amount;

        /** 进度状态 */
        @Excel(name = "进度状态")
        private String status;

        /** 经办人 */
        @Excel(name = "经办人")
        private String ownerName;

        /**
         * 从合同装配一行（空值统一写成空串，避免 Excel 里出现 {@code null} 文本）。
         *
         * @param contract 合同
         * @return 导出行
         */
        static ContractExportRow of(CtmsContract contract)
        {
            ContractExportRow row = new ContractExportRow();
            row.contractNo = contract.getContractNo();
            row.name = contract.getName();
            row.type = contract.getType();
            row.partyA = contract.getPartyA() == null ? "" : contract.getPartyA();
            row.partyB = contract.getPartyB() == null ? "" : contract.getPartyB();
            row.signDate = contract.getSignDate();
            row.amount = contract.getAmount();
            row.status = contract.getStatus();
            row.ownerName = contract.getOwnerName();
            return row;
        }

        public String getContractNo()
        {
            return contractNo;
        }

        public String getName()
        {
            return name;
        }

        public String getType()
        {
            return type;
        }

        public String getPartyA()
        {
            return partyA;
        }

        public String getPartyB()
        {
            return partyB;
        }

        public Date getSignDate()
        {
            return signDate;
        }

        public BigDecimal getAmount()
        {
            return amount;
        }

        public String getStatus()
        {
            return status;
        }

        public String getOwnerName()
        {
            return ownerName;
        }
    }

    /**
     * 框架详情：合同 + 子合同清单 + 子合同数量 + 子合同金额合计（与框架自身金额分开呈现）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:query')")
    @GetMapping(value = "/framework/{id:[A-Za-z0-9]+}")
    public AjaxResult getFrameworkInfo(@PathVariable("id") String id)
    {
        return success(contractService.selectFrameworkDetail(id));
    }

    /**
     * 查询某合同的字段级变更历史（分页）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}/change-logs")
    public TableDataInfo changeLogs(@PathVariable("id") String id)
    {
        CtmsChangeLog query = new CtmsChangeLog();
        query.setContractId(id);
        startPage();
        List<CtmsChangeLog> list = contractService.selectChangeLogList(query);
        return getDataTable(list);
    }

    /**
     * 查询某合同的行项列表。
     *
     * <p> 复用详情读取（{@code selectContractDetail}）：这样行项读取与详情走<b>同一处</b>
     * 数据范围判定，不需要再为"按合同取行项"开一条旁路。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract-item:list')")
    @GetMapping(value = "/items/{contractId:[A-Za-z0-9]+}")
    public AjaxResult items(@PathVariable("contractId") String contractId)
    {
        return success(contractService.selectContractDetail(contractId).getItems());
    }

    /**
     * 获取合同详情（含标签、行项、变更历史入口与只读「关联单据」区块）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(contractService.selectContractDetail(id));
    }

    /* ==================== 行项单行写（任务 7.4 追加：闭合 ctms:contract-item:add/edit/remove） ==================== */

    /**
     * <p> 新增一行行项（权限点 {@code ctms:contract-item:add}）。 </p>
     *
     * <p> 服务层复用既有「行项全量替换」链路（归一/校验/汇总/落库/变更历史都在同一处），
     * 并先做合同侧的存在性 + 数据范围 + 停用校验（范围外返回业务码 403）。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract-item:add')")
    @Log(title = "合同行项", businessType = BusinessType.INSERT)
    @PostMapping("/items")
    public AjaxResult addItem(@RequestBody CtmsContractItem item)
    {
        return success(contractItemWriter.insertContractItem(item));
    }

    /**
     * 编辑一行行项（权限点 {@code ctms:contract-item:edit}；按 {@code id} 反查所属合同后走同一处范围判定）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract-item:edit')")
    @Log(title = "合同行项", businessType = BusinessType.UPDATE)
    @PutMapping("/items")
    public AjaxResult editItem(@RequestBody CtmsContractItem item)
    {
        return success(contractItemWriter.updateContractItem(item));
    }

    /**
     * 删除一行行项（权限点 {@code ctms:contract-item:remove}）。
     *
     * <p> 路径变量收窄为 {@code [A-Za-z0-9]+}（与类注释里的路由冲突处理一致），
     * 与字面量段 {@code /items/{contractId}} 的 GET 靠 HTTP 方法区分。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract-item:remove')")
    @Log(title = "合同行项", businessType = BusinessType.DELETE)
    @DeleteMapping("/items/{id:[A-Za-z0-9]+}")
    public AjaxResult removeItem(@PathVariable("id") String id)
    {
        contractItemWriter.deleteContractItem(id);
        return toAjax(1);
    }

    /**
     * <p> 合同编号预览（2.0 B3 任务 5.8）：<b>不占号</b>，连续两次调用返回同一编号。 </p>
     *
     * <p> 路由用 {@code /next-no} 字面量，<b>不带</b>路径变量，因此与
     * {@code /{id:[A-Za-z0-9]+}} 不存在任何歧义（这一条比"靠正则收窄"更稳，见类注释）。 </p>
     *
     * <p> 权限点用 {@code ctms:contract:add}：预览的用途就是"登记前先拿号给用户看"，
     * 能登记才需要预览，不为它单独造权限点（新权限点必须同批进菜单 SQL，见 9.1 的双向核对）。 </p>
     *
     * @param type         合同类型（类型码，如 {@code SAL}）
     * @param subjectCode  我方主体码（如 {@code ZC}）
     * @param referenceDay 参考日期 {@code yyyy-MM-dd}（空则取当天；年份/月份码取它，不参与序号）
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:add')")
    @GetMapping("/next-no")
    public AjaxResult nextNo(@RequestParam(value = "type", required = false) String type,
                             @RequestParam(value = "subjectCode", required = false) String subjectCode,
                             @RequestParam(value = "referenceDay", required = false) String referenceDay)
    {
        return success(contractService.previewContractNo(type, subjectCode, referenceDay));
    }

    /**
     * <p> 质保到期提醒（2.0 B3 任务 5.6）：即将到期与已到期两个列表。 </p>
     *
     * <p> 权限点复用 {@code ctms:contract:list}：它是合同台账上的"再读一次"，
     * 不是新的资源域；这样也不必新增权限点与菜单行（保持 25 个 {@code ctms:*} 不变）。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:list')")
    @GetMapping("/warranty-reminders")
    public AjaxResult warrantyReminders()
    {
        return success(contractService.selectWarrantyReminders());
    }

    /**
     * 登记合同。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:add')")
    @Log(title = "合同台账", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CtmsContract contract)
    {
        contractService.insertContract(contract);
        return toAjax(1);
    }

    /**
     * 编辑合同。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:edit')")
    @Log(title = "合同台账", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CtmsContract contract)
    {
        contractService.updateContract(contract);
        return toAjax(1);
    }

    /**
     * <p> 进度状态 / 到货状态自由流转。 </p>
     *
     * <p> 改为「已终止」时的终止原因由请求体的 {@code deletedReason} 字段承载
     * （字段名复用只为承载该必填项，与软删除无关；详见服务实现的说明）。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:status')")
    @Log(title = "合同台账", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody CtmsContract contract)
    {
        return toAjax(contractService.changeStatus(contract));
    }

    /**
     * 释放质保。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:edit')")
    @Log(title = "合同台账", businessType = BusinessType.UPDATE)
    @PutMapping(value = "/warranty/{id:[A-Za-z0-9]+}/release")
    public AjaxResult releaseWarranty(@PathVariable("id") String id)
    {
        return toAjax(contractService.releaseWarranty(id));
    }

    /**
     * 停用（软删除）合同，停用原因必填。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:remove')")
    @Log(title = "合同台账", businessType = BusinessType.DELETE)
    @DeleteMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult remove(@PathVariable("id") String id,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        contractService.softDelete(id, reason);
        return toAjax(1);
    }

    /**
     * <p> 恢复合同（30 天窗口内）。 </p>
     *
     * <p> 服务层的恢复是幂等的（未停用时直接成功），所以这里先读一次详情拿到
     * {@code delFlag} 再决定提示语：未停用时返回 {@code msg = 该合同未停用}
     * （规格场景要求"返回成功且提示该合同未停用，不产生错误"）。
     * 这次读取本身也是数据范围校验（服务层强制），不构成旁路。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:remove')")
    @Log(title = "合同台账", businessType = BusinessType.UPDATE)
    @PutMapping(value = "/{id:[A-Za-z0-9]+}/restore")
    public AjaxResult restore(@PathVariable("id") String id)
    {
        CtmsContract before = contractService.selectContractDetail(id);
        boolean wasDeleted = before != null && "1".equals(before.getDelFlag());
        contractService.restore(id);
        return wasDeleted ? toAjax(1) : AjaxResult.success("该合同未停用");
    }
}
