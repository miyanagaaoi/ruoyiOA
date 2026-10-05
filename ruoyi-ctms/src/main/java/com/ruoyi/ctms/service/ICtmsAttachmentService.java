package com.ruoyi.ctms.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.ctms.domain.CtmsAttachment;

/**
 * <p> 附件业务语义服务（2.0 B3 任务 6.1~6.3；design D-5）。 </p>
 *
 * <p> <b>职责边界</b>：上传/存储/随机命名复用平台 {@code ruoyi-file}；
 * 本服务只负责"业务对象挂载 + 业务限制 + 鉴权 + 删除留痕"这一层：
 * ① 对象类型注册与对象存在性校验；② 更窄白名单 + ≤20MB（413）；
 * ③ 按对象的数据范围校验（范围外 403）；④ 删除写字段名 {@code 附件} 的变更历史。 </p>
 *
 * <p> <b>附件继承合同的数据范围</b>：所有按标识取数的方法都先调用
 * {@code ICtmsContractService.checkContractAccess}，
 * 因此"合同不可见的用户"同样看不到它的附件，不会出现越过合同范围的旁路。 </p>
 *
 * @author 二开
 */
public interface ICtmsAttachmentService
{
    /**
     * 查询某业务对象下的附件列表（只返回未删除的；按上传时间倒序）。
     *
     * @param objectType 对象类型（未注册即拒绝）
     * @param objectId   对象标识（对象不存在即拒绝）
     * @return 附件集合
     */
    List<CtmsAttachment> selectAttachmentList(String objectType, String objectId);

    /**
     * 上传附件并落挂载元数据。
     *
     * <p> 校验顺序（顺序本身就是口径：先花最少的代价挡住最荒谬的请求）：
     * 对象类型 → 对象标识 → 对象存在性与可见性 → 文件名与后缀 → 大小 → 落盘 → 落库。 </p>
     *
     * @param objectType 对象类型
     * @param objectId   对象标识
     * @param file       上传的文件（multipart 字段名 {@code file}）
     * @return 落库后的附件元数据（含 {@code storedPath} 与 {@code id}）
     */
    CtmsAttachment uploadAttachment(String objectType, String objectId, MultipartFile file);

    /**
     * 软删除附件，并写一条字段名 {@code 附件} 的变更历史（任务 6.3）。
     *
     * <p> 变更历史挂在该附件所属的合同上（{@code contract_id}），
     * 因此"合同详情的变更历史里能查到附件删除记录"。 </p>
     *
     * @param id 附件ID
     */
    void deleteAttachment(String id);

    /**
     * 校验下载权并返回附件元数据（范围外 403；已删除/不存在按"附件不存在"处理）。
     *
     * @param id 附件ID
     * @return 附件元数据
     */
    CtmsAttachment requireDownloadable(String id);

    /**
     * 解析附件的本地绝对路径（下载用）。
     *
     * @param attachment 附件元数据
     * @return 本地绝对路径
     */
    String localPathOf(CtmsAttachment attachment);

    /**
     * 对象类型是否已注册（控制器/前端提示用；判定口径见
     * {@code CtmsAttachmentRules.isRegisteredObjectType}）。
     *
     * @param objectType 对象类型
     * @return 已注册返回 true
     */
    boolean isRegisteredObjectType(String objectType);
}
