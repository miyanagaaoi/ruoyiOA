package com.ruoyi.serial.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.domain.CodeConfig;
import com.ruoyi.serial.module.CodeConfigDTO;
import com.ruoyi.serial.module.CodeGenContext;
import com.ruoyi.serial.module.CodeGenRequest;
import com.ruoyi.serial.service.ICodeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 编号配置Controller
 * 
 * @author wocurr.com
 */
@RestController
@RequestMapping("/serial/config")
public class CodeConfigController extends BaseController {
    @Autowired
    private ICodeConfigService codeConfigService;
    @Autowired
    private ICodeGenService codeGenService;

    /**
     * 查询编号配置列表
     */
    @PreAuthorize("@ss.hasPermi('serial:config:list')")
    @GetMapping("/list")
    public TableDataInfo list(CodeConfig codeConfig) {
        startPage();
        List<CodeConfig> list = codeConfigService.listCodeConfig(codeConfig);
        return getDataTable(list);
    }

    /**
     * 编号选项（所有有效的编号）
     * @return
     */
    @GetMapping("/serialOptions")
    public AjaxResult serialOptions() {
        return AjaxResult.success(codeConfigService.serialOptions());
    }

    /**
     * 获取编号配置详细信息
     */
    @PreAuthorize("@ss.hasPermi('serial:config:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") String id) {
        return success(codeConfigService.getCodeConfigById(id));
    }

    /**
     * 新增编号配置
     */
    @PreAuthorize("@ss.hasPermi('serial:config:add')")
    @Log(title = "编号配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CodeConfigDTO codeConfig) {
        return toAjax(codeConfigService.saveCodeConfig(codeConfig));
    }

    /**
     * 修改编号配置
     */
    @PreAuthorize("@ss.hasPermi('serial:config:edit')")
    @Log(title = "编号配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CodeConfigDTO codeConfig) {
        return toAjax(codeConfigService.updateCodeConfig(codeConfig));
    }

    /**
     * 启用禁用
     * @param codeConfig
     * @return
     */
    @PutMapping("/changeEnableFlag")
    public AjaxResult changeEnableFlag(@RequestBody CodeConfig codeConfig) {
        return toAjax(codeConfigService.changeEnableFlag(codeConfig));
    }

    /**
     * 删除编号配置
     */
    @PreAuthorize("@ss.hasPermi('serial:config:remove')")
    @Log(title = "编号配置", businessType = BusinessType.DELETE)
	@DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable String id) {
        return toAjax(codeConfigService.deleteCodeConfigById(id));
    }

    /**
     * 获取编号
     *
     * @param confId 规则配置id
     * @return
     */
    @GetMapping("/genSerialNo/{confId}")
    public AjaxResult getCodeNumber(@PathVariable("confId") String confId) {
        return AjaxResult.success("操作成功", codeGenService.getNextCode(confId));
    }

    /**
     * <p> 获取编号（带取号上下文，2.0 B3 §1.3 新增）。 </p>
     *
     * <p> 与上面的 GET 同路径、不同方法，互不影响：
     * 老的 GET 调用方（既有 11 类编号）行为完全不变。 </p>
     *
     * <p> 请求体：{@code {"referenceDate":"2025-06-01","params":{"typeCode":"PUR","subjectCode":"ZC"}}} </p>
     */
    @PostMapping("/genSerialNo/{confId}")
    public AjaxResult getCodeNumberWithContext(@PathVariable("confId") String confId,
                                               @RequestBody(required = false) CodeGenRequest request) {
        return AjaxResult.success("操作成功", codeGenService.getNextCode(confId, toContext(request)));
    }

    /**
     * <p> 预览下一个编号（<b>不占号</b>，2.0 B3 §1.3 新增）。 </p>
     *
     * <p> 返回"此刻取号会得到的编号"，但不会递增计数器、不写编号流水、不改配置表 —— 连续调用结果相同。 </p>
     */
    @PostMapping("/previewSerialNo/{confId}")
    public AjaxResult previewCodeNumber(@PathVariable("confId") String confId,
                                        @RequestBody(required = false) CodeGenRequest request) {
        return AjaxResult.success("操作成功", codeGenService.previewNextCode(confId, toContext(request)));
    }

    /**
     * HTTP 入参 → 服务层上下文。
     *
     * <p> 参考日期在这里显式解析（格式 {@code yyyy-MM-dd}），不依赖 Jackson 的全局日期格式，
     * 解析失败直接给出明确文案而不是静默取当天。 </p>
     */
    private CodeGenContext toContext(CodeGenRequest request) {
        if (request == null) {
            return null;
        }
        Date referenceDate = null;
        if (StringUtils.isNotBlank(request.getReferenceDate())) {
            referenceDate = DateUtils.parseDate(request.getReferenceDate().trim());
            if (referenceDate == null) {
                throw new BaseException("参考日期格式错误，应为 yyyy-MM-dd：" + request.getReferenceDate());
            }
        }
        Map<String, String> params = request.getParams();
        if (referenceDate == null && (params == null || params.isEmpty())) {
            // 什么都没传 → 走既有行为（不启用分桶、日期取当天）
            return null;
        }
        return new CodeGenContext(referenceDate, params);
    }
}
