package com.ruoyi.ctms.controller;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.service.ICtmsPartnerService;

/**
 * <p> 往来单位档案（客户 / 供应商）控制器（B3 §3）。 </p>
 *
 * <p> 权限点真源是 {@code sql/二开-合同台账-菜单.sql}，本类里的
 * {@code ctms:partner:list/query/add/edit/remove/status} 与之<b>逐字一致</b>；
 * 客户与供应商共用同一组权限点（菜单里"往来单位"是一个菜单，两个页签）。 </p>
 *
 * <p> ⚠ <b>路由冲突的显式处理</b>：{@code GET /customer/{id}} 与
 * {@code GET /customer/list}、{@code GET /customer/options} 在 Spring 里同属"匹配得上"的
 * 候选（{@code list}/{@code options} 恰好也是字母串）。这里把 {@code {id}} 收窄为
 * {@code {id:[A-Za-z0-9]+}}：一是让"档案标识只可能是 32 位十六进制 UUID"这条事实
 * 直接写进路由（本仓库 {@code IdUtils.fastSimpleUUID()} 产出的是大写十六进制），
 * 二是让路径变量候选与字面量候选的歧义在映射层面就收敛；
 * Spring 的模式比较器会优先选择字面量更具体的 {@code /list} 与 {@code /options}，
 * 因此三者可以安全共存。 </p>
 *
 * <p> 列表接口用 {@code startPage()} + {@code getDataTable(list)}；
 * 详情/选择器用 {@code success(obj)}；写接口用 {@code toAjax(...)}。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/ctms/partner")
public class CtmsPartnerController extends BaseController
{
    @Autowired
    private ICtmsPartnerService partnerService;

    /* ==================== 客户档案 ==================== */

    /**
     * 查询客户档案列表（含停用项，供档案管理页使用）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/customer/list")
    public TableDataInfo customerList(CtmsCustomer query)
    {
        startPage();
        List<CtmsCustomer> list = partnerService.selectCustomerList(query);
        return getDataTable(list);
    }

    /**
     * 查询启用中的客户档案（合同表单选择器专用）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping("/customer/options")
    public AjaxResult customerOptions()
    {
        return success(partnerService.selectCustomerOptions());
    }

    /**
     * 获取客户档案详情
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping(value = "/customer/{id:[A-Za-z0-9]+}")
    public AjaxResult getCustomerInfo(@PathVariable("id") String id)
    {
        return success(partnerService.selectCustomerById(id));
    }

    /**
     * 新增客户档案
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:add')")
    @Log(title = "客户档案", businessType = BusinessType.INSERT)
    @PostMapping("/customer")
    public AjaxResult addCustomer(@RequestBody CtmsCustomer customer)
    {
        partnerService.insertCustomer(customer);
        return toAjax(1);
    }

    /**
     * 修改客户档案
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:edit')")
    @Log(title = "客户档案", businessType = BusinessType.UPDATE)
    @PutMapping("/customer")
    public AjaxResult editCustomer(@RequestBody CtmsCustomer customer)
    {
        partnerService.updateCustomer(customer);
        return toAjax(1);
    }

    /**
     * 启停用客户档案（只改启用标志，不影响已引用它的历史合同）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:status')")
    @Log(title = "客户档案", businessType = BusinessType.UPDATE)
    @PutMapping("/customer/status")
    public AjaxResult changeCustomerStatus(@RequestBody CtmsCustomer customer)
    {
        return toAjax(partnerService.changeCustomerStatus(customer.getId(), customer.getEnableFlag()));
    }

    /**
     * 删除客户档案（仅在零引用时放行）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:remove')")
    @Log(title = "客户档案", businessType = BusinessType.DELETE)
    @DeleteMapping(value = "/customer/{id:[A-Za-z0-9]+}")
    public AjaxResult removeCustomer(@PathVariable("id") String id)
    {
        partnerService.deleteCustomerById(id);
        return toAjax(1);
    }

    /* ==================== 供应商档案 ==================== */

    /**
     * 查询供应商档案列表（含停用项，供档案管理页使用）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/supplier/list")
    public TableDataInfo supplierList(CtmsSupplier query)
    {
        startPage();
        List<CtmsSupplier> list = partnerService.selectSupplierList(query);
        return getDataTable(list);
    }

    /**
     * 查询启用中的供应商档案（合同表单选择器专用）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping("/supplier/options")
    public AjaxResult supplierOptions()
    {
        return success(partnerService.selectSupplierOptions());
    }

    /**
     * 获取供应商档案详情
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping(value = "/supplier/{id:[A-Za-z0-9]+}")
    public AjaxResult getSupplierInfo(@PathVariable("id") String id)
    {
        return success(partnerService.selectSupplierById(id));
    }

    /**
     * 新增供应商档案
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:add')")
    @Log(title = "供应商档案", businessType = BusinessType.INSERT)
    @PostMapping("/supplier")
    public AjaxResult addSupplier(@RequestBody CtmsSupplier supplier)
    {
        partnerService.insertSupplier(supplier);
        return toAjax(1);
    }

    /**
     * 修改供应商档案
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:edit')")
    @Log(title = "供应商档案", businessType = BusinessType.UPDATE)
    @PutMapping("/supplier")
    public AjaxResult editSupplier(@RequestBody CtmsSupplier supplier)
    {
        partnerService.updateSupplier(supplier);
        return toAjax(1);
    }

    /**
     * 启停用供应商档案（只改启用标志，不影响已引用它的历史合同）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:status')")
    @Log(title = "供应商档案", businessType = BusinessType.UPDATE)
    @PutMapping("/supplier/status")
    public AjaxResult changeSupplierStatus(@RequestBody CtmsSupplier supplier)
    {
        return toAjax(partnerService.changeSupplierStatus(supplier.getId(), supplier.getEnableFlag()));
    }

    /**
     * 删除供应商档案（仅在零引用时放行）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:remove')")
    @Log(title = "供应商档案", businessType = BusinessType.DELETE)
    @DeleteMapping(value = "/supplier/{id:[A-Za-z0-9]+}")
    public AjaxResult removeSupplier(@PathVariable("id") String id)
    {
        partnerService.deleteSupplierById(id);
        return toAjax(1);
    }
}
