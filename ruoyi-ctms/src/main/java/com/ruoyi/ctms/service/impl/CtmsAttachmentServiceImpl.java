package com.ruoyi.ctms.service.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsAttachment;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.mapper.CtmsAttachmentMapper;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.service.ICtmsAttachmentService;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.support.ContractDataScope;
import com.ruoyi.ctms.support.CtmsAttachmentObjectTypes;
import com.ruoyi.ctms.support.CtmsAttachmentRules;
import com.ruoyi.ctms.support.ContractRules;

/**
 * <p> 附件业务语义实现（2.0 B3 任务 6.1~6.3；design D-5、note
 * {@code notes/attachment-reuse.md} 的 §3 清单）。 </p>
 *
 * <p> <b>与平台的分工</b>：字节流、随机命名、日期目录分片、{@code /profile/**} 静态取回
 * 全部走 {@code FileUploadUtils}（平台能力，一行不改）；本类只做平台给不了的四件事：
 * 更窄白名单、≤20MB + 413、按对象鉴权、删除留痕。 </p>
 *
 * <p> <b>为什么"先查大小再落盘"</b>：平台 {@code assertAllowed} 的上限是 50MB，
 * 直接调它会让 25MB 的文件先写进磁盘再靠我们事后发现（半成品）；
 * 所以我们在调用前先用 {@link CtmsAttachmentRules#checkSize} 判一次 20MB。
 * 事后还补一次"落盘后复检 + 清理"，这样即使平台的顺序将来变了，
 * 也<b>不会留下半成品</b>（任务 6.2 的断言正是"超限后半成品文件不存在"）。 </p>
 *
 * <p> <b>提示文案是接口契约</b>：文案集中在 {@link CtmsAttachmentRules} 与 {@code CtmsContractServiceImpl}，
 * 验收脚本按关键字断言，改文案必须同步改脚本。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsAttachmentServiceImpl implements ICtmsAttachmentService
{
    /** 变更历史里的附件字段名（参考侧口径，见 design D-10） */
    public static final String FIELD_ATTACHMENT = "附件";

    @Autowired
    private CtmsAttachmentMapper attachmentMapper;

    /**
     * <p> 合同服务：<b>只用来做数据范围校验</b>（{@code checkContractAccess}）
     * 与回读合同（写变更历史时取 contractId）。 </p>
     *
     * <p> 附件的数据范围判定刻意不自己写一份：判定口径只允许有一处
     * （{@code ContractDataScope} + {@code CtmsContractServiceImpl.canAccess}），
     * 复制一份必然漂移。 </p>
     */
    @Autowired
    private ICtmsContractService contractService;

    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /* ==================== 查询 ==================== */

    @Override
    public List<CtmsAttachment> selectAttachmentList(String objectType, String objectId)
    {
        String type = checkObjectType(objectType);
        String id = checkObjectId(objectId);
        requireObjectAccess(type, id);
        List<CtmsAttachment> list = attachmentMapper.selectAttachmentList(type, id, contractDataScopeSql());
        return list == null ? new ArrayList<CtmsAttachment>() : list;
    }

    /* ==================== 上传 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CtmsAttachment uploadAttachment(String objectType, String objectId, MultipartFile file)
    {
        String type = checkObjectType(objectType);
        String id = checkObjectId(objectId);
        requireObjectAccess(type, id);

        if (file == null || file.isEmpty())
        {
            throw new ServiceException("上传文件不能为空");
        }
        String originalName = CtmsAttachmentRules.checkFileName(file.getOriginalFilename());
        // ① 先按 CTMS 的 20MB 判一次（平台是 50MB，见类注释）→ 超限直接 413，且**尚未落盘**
        CtmsAttachmentRules.checkSize(file.getSize());

        String storedPath = store(type, id, file, originalName);
        try
        {
            // ② 落盘后复检：万一平台的顺序将来变成"先写后校验"，这里就是最后一道闸
            cleanupIfOversized(storedPath, file.getSize());
            return persist(type, id, file, originalName, storedPath);
        }
        catch (RuntimeException e)
        {
            // ③ 落库失败就把刚写下的物理文件删掉，避免"磁盘有文件、库里没元数据"的孤儿
            deleteQuietly(storedPath);
            throw e;
        }
    }

    /**
     * 调用平台上传能力把文件写进 {@code profile/upload}，返回相对路径。
     *
     * <p> <b>用平台的 {@code assertAllowed} 做最后一道平台侧白名单/长度校验，
     * 但不用平台的 {@code extractFilename}</b>：后者对非 ASCII 文件名会退化成空主名
     * （实测中文名变成 {@code .pdf}），所以存储名由
     * {@link CtmsAttachmentRules#storedNameOf} 生成（口径与平台一致：日期目录 + 原名 + 时间戳随机段）。 </p>
     *
     * <p> 这里再判一次大小：平台 {@code assertAllowed} 的上限是 50MB，
     * 用它当"兜底闸"可以保证即使调用顺序被改坏也写不进超限文件。 </p>
     *
     * @param type         对象类型
     * @param id           对象标识
     * @param file         上传文件
     * @param originalName 已校验的原始文件名
     * @return 形如 {@code /profile/upload/2026/10/05/xxx.pdf} 的相对路径
     */
    private String store(String type, String id, MultipartFile file, String originalName)
    {
        File target = null;
        try
        {
            CtmsAttachmentRules.checkSize(Long.valueOf(file.getSize()));
            FileUploadUtils.assertAllowed(file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
            String fileName = CtmsAttachmentRules.storedNameOf(originalName);
            target = FileUploadUtils.getAbsoluteFile(RuoYiConfig.getUploadPath(), fileName);
            file.transferTo(target.toPath());
            return FileUploadUtils.getPathFileName(RuoYiConfig.getUploadPath(), fileName);
        }
        catch (ServiceException e)
        {
            // 业务拒绝（含 413）：把可能已经写下的部分字节清掉，不留半成品
            deleteFile(target);
            throw e;
        }
        catch (Exception e)
        {
            deleteFile(target);
            throw new ServiceException("附件上传失败：" + describe(type, id)
                    + "，文件[" + originalName + "]原因：" + e.getMessage());
        }
    }

    /**
     * 删除一个已定位的物理文件（尽力而为，失败不改变业务结果）。
     *
     * @param file 目标文件；null 直接返回
     */
    private void deleteFile(File file)
    {
        if (file == null || !file.exists())
        {
            return;
        }
        if (!file.delete())
        {
            file.deleteOnExit();
        }
    }

    /**
     * 落一条挂载元数据（对象类型/标识、原始名、相对路径、内容类型、字节数、上传人）。
     *
     * @param type         对象类型
     * @param id           对象标识
     * @param file         上传文件
     * @param originalName 原始文件名
     * @param storedPath   存储相对路径
     * @return 已落库的附件
     */
    private CtmsAttachment persist(String type, String id, MultipartFile file,
                                   String originalName, String storedPath)
    {
        CtmsAttachment attachment = new CtmsAttachment();
        attachment.setId(IdUtils.fastSimpleUUID());
        attachment.setObjectType(type);
        attachment.setObjectId(id);
        // 合同侧对象才写 contract_id（单据附件为 null），与 DDL 的可空口径一致
        attachment.setContractId(CtmsAttachmentObjectTypes.CONTRACT.equals(type) ? id : null);
        attachment.setFileName(originalName);
        attachment.setStoredPath(storedPath);
        attachment.setContentType(truncate(file.getContentType(), 128));
        attachment.setSizeBytes(file.getSize());
        attachment.setDelFlag(CtmsAttachment.DEL_FLAG_NORMAL);
        attachment.setCreateId(currentUserId());
        attachment.setCreateBy(currentUsername());
        if (attachmentMapper.insertAttachment(attachment) <= 0)
        {
            throw new ServiceException("附件元数据保存失败");
        }
        return attachment;
    }

    /* ==================== 删除（留痕） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(String id)
    {
        CtmsAttachment attachment = requireExisting(id);
        requireObjectAccess(attachment.getObjectType(), attachment.getObjectId());
        if (attachmentMapper.softDeleteAttachment(attachment.getId(), currentUserId(), currentUsername()) <= 0)
        {
            // 并发下别人已经删掉：按幂等成功处理更友好，但这里必须让调用方知道"没删到"
            throw new ServiceException("附件不存在或已删除");
        }
        writeDeleteLog(attachment);
    }

    /**
     * 写一条字段名 {@code 附件} 的变更历史（任务 6.3）。
     *
     * <p> 挂在该附件所属合同上（{@code contract_id} 与 {@code object_id} 同值），
     * 因此合同详情的变更历史面板里能直接看到；旧值是"文件名（大小）"，
     * 新值为空表示该附件被移除。 </p>
     *
     * <p> 单据侧对象（B4）没有合同，此时只写多态定位、不写 {@code contract_id}
     * （{@code t_ctms_contract.contract_id} 本就允许为空，见 DDL 注释）。 </p>
     *
     * @param attachment 被删除的附件（删除前的元数据）
     */
    private void writeDeleteLog(CtmsAttachment attachment)
    {
        String contractId = CtmsAttachmentObjectTypes.CONTRACT.equals(attachment.getObjectType())
                ? attachment.getObjectId() : null;
        CtmsChangeLog log = new CtmsChangeLog();
        log.setId(IdUtils.fastSimpleUUID());
        log.setContractId(contractId);
        log.setFieldName(FIELD_ATTACHMENT);
        log.setOldValue(attachment.getFileName()
                + "（" + CtmsAttachmentRules.sizeText(attachment.getSizeBytes()) + "）");
        log.setNewValue(null);
        log.setNote("删除附件");
        log.setSource(CtmsChangeLog.SOURCE_MANUAL);
        log.setOperatorId(currentUserId());
        log.setOperatorName(currentNickName());
        log.setObjectType(attachment.getObjectType());
        log.setObjectId(attachment.getObjectId());
        log.setCreateId(currentUserId());
        log.setCreateBy(currentUsername());
        changeLogMapper.insertChangeLog(log);
    }

    /* ==================== 下载 ==================== */

    @Override
    public CtmsAttachment requireDownloadable(String id)
    {
        CtmsAttachment attachment = requireExisting(id);
        requireObjectAccess(attachment.getObjectType(), attachment.getObjectId());
        return attachment;
    }

    @Override
    public String localPathOf(CtmsAttachment attachment)
    {
        if (attachment == null || ContractRules.isBlank(attachment.getStoredPath()))
        {
            throw new ServiceException("附件文件不存在");
        }
        String relative = StringUtils.substringAfter(attachment.getStoredPath(), Constants.RESOURCE_PREFIX);
        if (ContractRules.isBlank(relative))
        {
            throw new ServiceException("附件文件不存在");
        }
        return RuoYiConfig.getProfile() + relative;
    }

    /* ==================== 对象类型与存在性 ==================== */

    @Override
    public boolean isRegisteredObjectType(String objectType)
    {
        return CtmsAttachmentRules.isRegisteredObjectType(objectType);
    }

    /**
     * 校验对象类型（未注册即拒绝）。
     *
     * @param objectType 对象类型
     * @return 归一后的对象类型
     */
    private String checkObjectType(String objectType)
    {
        return CtmsAttachmentRules.checkObjectType(objectType);
    }

    /**
     * 校验对象标识（必填）。
     *
     * @param objectId 对象标识
     * @return 去空白后的对象标识
     */
    private String checkObjectId(String objectId)
    {
        return CtmsAttachmentRules.checkObjectId(objectId);
    }

    /**
     * <p> 对象存在性 + 可见性校验（任务 6.1 的"对象不存在被拒"）。 </p>
     *
     * <p> 分派点：合同对象走 {@code ICtmsContractService.checkContractAccess}，
     * 该方法在对象不存在时抛「合同不存在」、范围外抛业务码 403；
     * B4 的单据对象在这里追加分支（对象类型常量在
     * {@link CtmsAttachmentObjectTypes}，两边必须同批改）。 </p>
     *
     * <p> 未注册的类型在上一步就已被拒，因此走到这里的分支一定是已登记的；
     * 仍然显式抛错是为了让"注册了类型却忘了写校验分支"变成一次明确的失败，
     * 而不是静默放过。 </p>
     *
     * @param objectType 对象类型（已归一）
     * @param objectId   对象标识
     */
    private void requireObjectAccess(String objectType, String objectId)
    {
        if (CtmsAttachmentObjectTypes.CONTRACT.equals(objectType))
        {
            contractService.checkContractAccess(objectId);
            return;
        }
        throw new ServiceException("未注册的对象类型：" + objectType);
    }

    /**
     * 数据范围片段（合同维度）。附件列表用它把"看不见的合同"的附件一起排掉。
     *
     * @return 白名单片段；"全部数据"档返回空串（不加条件）
     */
    private String contractDataScopeSql()
    {
        try
        {
            return ContractDataScope.buildDataScopeSql();
        }
        catch (Exception e)
        {
            // 无登录上下文（定时任务/迁移脚本）时不加范围条件：与既有实现同款兜底
            return null;
        }
    }

    /* ==================== 小工具 ==================== */

    /**
     * 按标识取未删除的附件行。
     *
     * @param id 附件ID
     * @return 附件
     * @throws ServiceException 标识为空或行不存在/已删除
     */
    private CtmsAttachment requireExisting(String id)
    {
        if (ContractRules.isBlank(id))
        {
            throw new ServiceException("附件不存在");
        }
        CtmsAttachment attachment = attachmentMapper.selectAttachmentById(id);
        if (attachment == null)
        {
            throw new ServiceException("附件不存在");
        }
        return attachment;
    }

    /**
     * 落盘后复检大小：超限则删掉刚写出的物理文件并按 413 拒绝。
     *
     * <p> 正常路径下这一步不会触发（前面已判过），它是"顺序被改坏"时的兜底，
     * 同时是任务 6.2「超限后半成品文件不存在」这条断言的直接实现点。 </p>
     *
     * @param storedPath 存储相对路径
     * @param size       声明的字节数
     */
    private void cleanupIfOversized(String storedPath, long size)
    {
        if (!CtmsAttachmentRules.exceedsMaxSize(size))
        {
            return;
        }
        deleteQuietly(storedPath);
        CtmsAttachmentRules.checkSize(size);
    }

    /**
     * 删除物理文件（静默失败：清理是尽力而为，不能把正常业务异常盖掉）。
     *
     * @param storedPath 存储相对路径
     */
    private void deleteQuietly(String storedPath)
    {
        if (ContractRules.isBlank(storedPath))
        {
            return;
        }
        try
        {
            String relative = StringUtils.substringAfter(storedPath, Constants.RESOURCE_PREFIX);
            if (ContractRules.isBlank(relative))
            {
                return;
            }
            File file = new File(RuoYiConfig.getProfile() + relative);
            if (file.exists() && !file.delete())
            {
                // 删不掉不影响主流程：文件不会被任何元数据引用，且下载接口只认元数据
                file.deleteOnExit();
            }
        }
        catch (Exception ignored)
        {
            // 同上：清理失败不改变业务结果
        }
    }

    /**
     * 对象描述（错误文案里定位"给哪个对象传文件失败了"）。
     *
     * @param objectType 对象类型
     * @param objectId   对象标识
     * @return 形如 {@code contract/ABC}
     */
    private String describe(String objectType, String objectId)
    {
        return objectType + "/" + objectId;
    }

    /**
     * 按列宽截断（{@code content_type varchar(128)}）。
     *
     * @param value 原始值
     * @param limit 列宽
     * @return 截断后的值；空返回 null
     */
    private String truncate(String value, int limit)
    {
        String text = ContractRules.trimToNull(value);
        if (text == null)
        {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /**
     * 当前用户ID（无登录上下文回落为 {@code 1}，与合同服务同款口径）。
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        try
        {
            String userId = com.ruoyi.common.utils.SecurityUtils.getUserId();
            return ContractRules.isBlank(userId) ? "1" : userId;
        }
        catch (Exception e)
        {
            return "1";
        }
    }

    /**
     * 当前用户登录名快照（DDL 的 {@code create_by}）。
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        try
        {
            String username = com.ruoyi.common.utils.SecurityUtils.getUsername();
            return ContractRules.isBlank(username) ? "system" : username;
        }
        catch (Exception e)
        {
            return "system";
        }
    }

    /**
     * 当前用户姓名快照（变更历史的 {@code operator_name}；拿不到返回 null，不伪造）。
     *
     * @return 姓名；无登录上下文返回 null
     */
    protected String currentNickName()
    {
        try
        {
            com.ruoyi.common.core.domain.model.LoginUser loginUser =
                    com.ruoyi.common.utils.SecurityUtils.getLoginUser();
            if (loginUser == null || loginUser.getUser() == null)
            {
                return null;
            }
            return ContractRules.trimToNull(loginUser.getUser().getNickName());
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
