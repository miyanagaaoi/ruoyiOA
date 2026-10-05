package com.ruoyi.ctms.controller;

import java.io.File;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.ctms.domain.CtmsAttachment;
import com.ruoyi.ctms.service.ICtmsAttachmentService;
import com.ruoyi.ctms.support.CtmsAttachmentObjectTypes;
import com.ruoyi.ctms.support.CtmsAttachmentRules;

/**
 * <p> 合同附件控制器（2.0 B3 任务 6.1~6.3；design D-5）。 </p>
 *
 * <p> <b>权限点</b>（第 6 组新增 <b>1 个</b>：{@code ctms:attachment:list}，菜单 SQL 由
 * 25 个 {@code ctms:*} 变为 26 个，行数 26 → 27）：
 * <ul>
 *   <li>读（列表 / 下载）→ {@code ctms:attachment:list}；</li>
 *   <li>写（上传 / 删除）→ {@code ctms:contract:edit} <b>且</b> {@code ctms:attachment:list}。</li>
 * </ul>
 * 写动作要两个权限点，是为了堵住"能挂附件却看不见附件"这种自相矛盾的角色：
 * 附件有独立的存储与生命周期（不同于合同的一条普通字段），所以要自己的读权限点；
 * 但它挂在合同上，所以"改合同"的权限仍然必要。权限点真源仍是菜单 SQL，
 * 任务 9.1 的集合双向核对按 26 个执行。 </p>
 *
 * <p> <b>数据范围</b>：每个入口都先经 {@code ICtmsContractService.checkContractAccess}
 * （对象存在 + 合同数据范围），范围外返回业务码 403。列表另在 SQL 侧用同一处
 * 白名单片段过滤，二者语义一致。 </p>
 *
 * <p> <b>413 的返回形态</b>：{@link CtmsAttachmentRules#checkSize} 抛的
 * {@code ServiceException} 带业务码 413，这里捕获后<b>同时</b>把 HTTP 状态置为 413
 * （{@code MaxUploadSizeExceededException} 也走同一分支）——
 * 任务 6.2 要求的是 HTTP 语义上的 413，而不是"HTTP 200 + body 里写 413"。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/ctms/attachment")
public class CtmsAttachmentController extends BaseController
{
    @Autowired
    private ICtmsAttachmentService attachmentService;

    /**
     * 某业务对象下的附件列表（只含未删除）。
     *
     * @param objectType 对象类型（如 {@code contract}）
     * @param objectId   对象标识（如合同ID）
     */
    @PreAuthorize("@ss.hasPermi('ctms:attachment:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam("objectType") String objectType,
                           @RequestParam("objectId") String objectId)
    {
        List<CtmsAttachment> list = attachmentService.selectAttachmentList(objectType, objectId);
        return success(list);
    }

    /**
     * 上传附件（multipart；字段名 {@code file}，与平台 {@code /common/upload} 一致，
     * 前端可以复用同一套上传组件与 FormData 写法）。
     *
     * @param objectType 对象类型
     * @param objectId   对象标识
     * @param file       文件
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:edit') and @ss.hasPermi('ctms:attachment:list')")
    @Log(title = "合同附件", businessType = BusinessType.INSERT)
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("objectType") String objectType,
                             @RequestParam("objectId") String objectId,
                             @RequestParam("file") MultipartFile file,
                             HttpServletResponse response)
    {
        try
        {
            return success(attachmentService.uploadAttachment(objectType, objectId, file));
        }
        catch (ServiceException e)
        {
            return tooLargeOrFail(e, response);
        }
        catch (MaxUploadSizeExceededException e)
        {
            // 容器层上限（application.yml 的 500MB）被击穿：语义同样是 413
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return AjaxResult.error(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "上传的文件大小超出限制，单个附件不能超过 " + CtmsAttachmentRules.MAX_SIZE_MB + "MB");
        }
    }

    /**
     * 下载附件（<b>鉴权发生在返回字节之前</b>）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:attachment:list')")
    @GetMapping("/{id:[A-Za-z0-9]+}/download")
    public void download(@PathVariable("id") String id, HttpServletResponse response) throws Exception
    {
        CtmsAttachment attachment = attachmentService.requireDownloadable(id);
        String localPath = attachmentService.localPathOf(attachment);
        if (!new File(localPath).exists())
        {
            throw new ServiceException("附件文件不存在");
        }
        response.setContentType(attachment.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : attachment.getContentType());
        FileUtils.setAttachmentResponseHeader(response, attachment.getFileName());
        FileUtils.writeBytes(localPath, response.getOutputStream());
    }

    /**
     * 删除附件（软删除 + 写字段名 {@code 附件} 的变更历史）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:contract:edit') and @ss.hasPermi('ctms:attachment:list')")
    @Log(title = "合同附件", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id:[A-Za-z0-9]+}")
    public AjaxResult remove(@PathVariable("id") String id)
    {
        attachmentService.deleteAttachment(id);
        return toAjax(1);
    }

    /**
     * 已注册的对象类型清单（前端提示"这个对象能不能挂附件"；B4 交付后含 8 类单据对象）。
     *
     * <p> {@code plannedB4} 键保持不变（避免破坏既有消费者），值为<b>空数组</b> ——
     * B4 的待补对象类型已全部挪进 {@code registered}（含任务 6.6 补的 {@code stock_transfer}
     * 与 t19 补的两个申请单 {@code purchase_request}/{@code sales_request}），
     * 因此 {@code registered} 恰为 <b>9 项 = 合同 + 8 类单据</b>。 </p>
     *
     * <p> 新增 {@code docObjectTypes} 供前端/验收脚本直接断言"8 类单据都在册"；
     * 恒等式 {@code registered.Count = docObjectTypes.Count + 1} 由验收脚本逐项核对。 </p>
     */
    @PreAuthorize("@ss.hasPermi('ctms:attachment:list')")
    @GetMapping("/object-types")
    public AjaxResult objectTypes()
    {
        AjaxResult ajax = AjaxResult.success();
        ajax.put("registered", CtmsAttachmentObjectTypes.registered());
        ajax.put("plannedB4", CtmsAttachmentObjectTypes.plannedB4());
        ajax.put("docObjectTypes", CtmsAttachmentObjectTypes.docObjectTypes());
        ajax.put("maxSizeBytes", CtmsAttachmentRules.MAX_SIZE_BYTES);
        ajax.put("maxSizeMb", CtmsAttachmentRules.MAX_SIZE_MB);
        ajax.put("extensions", CtmsAttachmentRules.ALLOWED_EXTENSIONS);
        return ajax;
    }

    /**
     * 业务异常到 HTTP 的映射：413 走真实 HTTP 413，其余原样交给全局处理器。
     *
     * @param e        业务异常
     * @param response 响应
     * @return 响应体
     */
    private AjaxResult tooLargeOrFail(ServiceException e, HttpServletResponse response)
    {
        Integer code = e.getCode();
        if (code != null && code.intValue() == CtmsAttachmentRules.HTTP_PAYLOAD_TOO_LARGE)
        {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return AjaxResult.error(code, StringUtils.isBlank(e.getMessage())
                    ? "上传的文件大小超出限制" : e.getMessage());
        }
        // 非 413：不在这里吞，交给 GlobalExceptionHandler（保持"同类异常一种形态"）
        throw e;
    }
}
