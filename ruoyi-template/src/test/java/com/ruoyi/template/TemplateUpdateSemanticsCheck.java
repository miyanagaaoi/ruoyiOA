package com.ruoyi.template;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.template.domain.Template;
import com.ruoyi.template.domain.TemplateAttachment;
import com.ruoyi.template.domain.TemplateFlowAdmin;
import com.ruoyi.template.domain.TemplateMainText;
import com.ruoyi.template.domain.TemplateMessageNotice;
import com.ruoyi.template.domain.TemplateSubmitScope;
import com.ruoyi.template.mapper.TemplateAttachmentMapper;
import com.ruoyi.template.mapper.TemplateFlowAdminMapper;
import com.ruoyi.template.mapper.TemplateMainTextMapper;
import com.ruoyi.template.mapper.TemplateMapper;
import com.ruoyi.template.mapper.TemplateMessageNoticeMapper;
import com.ruoyi.template.mapper.TemplateSubmitScopeMapper;
import com.ruoyi.template.module.TemplateDTO;
import com.ruoyi.template.service.impl.TemplateAttachmentServiceImpl;
import com.ruoyi.template.service.impl.TemplateMainTextServiceImpl;
import com.ruoyi.template.service.impl.TemplateMessageNoticeServiceImpl;
import com.ruoyi.template.service.impl.TemplateServiceImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> <b>B1 §1.5 服务层自检：{@code updateTemplate} 必须"原地 UPDATE 同一行（id 不变）"。</b> </p>
 *
 * <p> 这是本批次的前置语义修正（REQ-DATA-004 / AC-68）：历史实现是
 * "旧行置 enable_flag='0'+del_flag='1'、再插入一条新 UUID"，会让所有 {@code template_id}
 * 外键表在每次编辑模板后集体失联。 </p>
 *
 * <p> 本程序不连数据库、不起 Spring：用内存桩替换 4 个 Mapper，直接调**真实**的
 * {@code TemplateServiceImpl.updateTemplate} 与三个配置 ServiceImpl，
 * 断言 ① 编辑后 id 不变且没有新增模板行；② 既有子表关联仍能按该 id 读到；
 * ③ 反复编辑不会在配置表里堆出第二行（多行会让返回单对象的读取器抛
 * TooManyResultsException，编辑页直接 500）。 </p>
 *
 * <p> <b>自证有效</b>：把 {@code TemplateServiceImpl.updateTemplate} 改回"换 ID"实现，
 * 断言 {@code insertCount == 0} 必然失败（换 ID 实现一定会调用 insertTemplate）。 </p>
 *
 * <p> 运行方式（classpath 见 ruoyi-vue-oa-master 目录下的 cp.txt）： </p>
 * <pre>
 *   mvn -B -DskipTests -pl ruoyi-template test-compile
 *   mvn -B -q -pl ruoyi-template dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "ruoyi-template\target\test-classes;ruoyi-template\target\classes;$(cat cp.txt)" `
 *        com.ruoyi.template.TemplateUpdateSemanticsCheck
 * </pre>
 *
 * @author 二开（2.0 B1）
 */
public class TemplateUpdateSemanticsCheck {

    private static final String TPL_ID = "TPL-B1-SEMANTICS-0001";

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        // ---------- 1. 内存"库"：1 条模板 + 3 类配置各 1 条 ----------
        StubTemplateMapper templateMapper = new StubTemplateMapper();
        Template before = new Template();
        before.setId(TPL_ID);
        before.setName("旧名称");
        before.setType("TYPE-1");
        before.setDefKey("flow_b1");
        before.setFormType("2");            // 业务表单：绕开动态表单分支
        before.setAttachFlag("1");
        before.setMainTextFlag("0");
        before.setMessageNoticeFlag("0");
        before.setEnableFlag("1");
        before.setDelFlag("0");
        before.setSort(3);
        before.setCreateId("u-create");
        before.setCreateBy("创建人");
        before.setCreateTime(new Date(1700000000000L));
        templateMapper.rows.put(TPL_ID, before);

        StubAttachmentMapper attachmentMapper = new StubAttachmentMapper();
        StubMainTextMapper mainTextMapper = new StubMainTextMapper();
        StubMessageNoticeMapper messageNoticeMapper = new StubMessageNoticeMapper();

        // 真实 Service 实现 + 注入桩 Mapper（这样"同一模板只保留一套配置"的不变量是被真正执行到的）
        TemplateAttachmentServiceImpl attachmentService = new TemplateAttachmentServiceImpl();
        inject(attachmentService, "templateAttachmentMapper", attachmentMapper);
        TemplateMainTextServiceImpl mainTextService = new TemplateMainTextServiceImpl();
        inject(mainTextService, "templateMainTextMapper", mainTextMapper);
        TemplateMessageNoticeServiceImpl messageNoticeService = new TemplateMessageNoticeServiceImpl();
        inject(messageNoticeService, "templateMessageNoticeMapper", messageNoticeMapper);

        TemplateServiceImpl service = new TemplateServiceImpl();
        inject(service, "templateMapper", templateMapper);
        inject(service, "templateAttachmentService", attachmentService);
        inject(service, "templateMainTextService", mainTextService);
        inject(service, "templateMessageNoticeService", messageNoticeService);
        // 2.0 B1 §3.1 新增的两个明细 Mapper 也要给桩（否则 update 链路会 NPE）
        inject(service, "templateSubmitScopeMapper", new StubSubmitScopeMapper());
        inject(service, "templateFlowAdminMapper", new StubFlowAdminMapper());

        // ---------- 2. 登录上下文（SecurityUtils 走 SecurityContextHolder） ----------
        SysUser sysUser = new SysUser();
        sysUser.setUserId("u-editor");
        sysUser.setDeptId("d-1");
        sysUser.setUserName("editor");
        sysUser.setNickName("编辑者");
        LoginUser loginUser = new LoginUser("u-editor", "d-1", sysUser, new HashSet<String>());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));

        // ---------- 3. 第一次编辑 ----------
        TemplateDTO dto = new TemplateDTO();
        dto.setId(TPL_ID);
        dto.setName("新名称");
        dto.setType("TYPE-1");
        dto.setDefKey("flow_b1");
        dto.setFormType("2");
        dto.setAttachFlag("1");
        dto.setMainTextFlag("0");
        dto.setMessageNoticeFlag("0");
        dto.setSort(9);
        TemplateAttachment attachment = new TemplateAttachment();
        attachment.setName("附件A");
        attachment.setLimitSize("10");
        dto.setAttachment(attachment);

        int affected = service.updateTemplate(dto);

        check("① updateTemplate 返回受影响行数 1", affected == 1, "实际=" + affected);
        check("② 没有新增模板行（换 ID 实现会调用 insertTemplate）",
                templateMapper.insertCount == 0, "insertTemplate 被调用 " + templateMapper.insertCount + " 次");
        check("③ 走的是一次原地 UPDATE", templateMapper.updateCount == 1,
                "updateTemplate 被调用 " + templateMapper.updateCount + " 次");
        check("④ 模板表仍只有 1 行", templateMapper.rows.size() == 1,
                "行数=" + templateMapper.rows.size());
        Template after = templateMapper.rows.get(TPL_ID);
        check("⑤ 编辑后 id 与编辑前一致（行还在同一个 id 上）", after != null && TPL_ID.equals(after.getId()),
                "实际 id=" + (after == null ? "null" : after.getId()));
        check("⑥ 业务字段已更新", after != null && "新名称".equals(after.getName())
                        && Integer.valueOf(9).equals(after.getSort()),
                after == null ? "无行" : ("name=" + after.getName() + ", sort=" + after.getSort()));
        check("⑦ 创建信息未被编辑覆盖",
                after != null && "u-create".equals(after.getCreateId())
                        && "创建人".equals(after.getCreateBy())
                        && after.getCreateTime() != null
                        && after.getCreateTime().getTime() == 1700000000000L,
                after == null ? "无行" : ("createId=" + after.getCreateId() + ", createBy=" + after.getCreateBy()));
        check("⑧ 编辑不改动启用/删除状态",
                after != null && "1".equals(after.getEnableFlag()) && "0".equals(after.getDelFlag()),
                after == null ? "无行" : ("enable=" + after.getEnableFlag() + ", del=" + after.getDelFlag()));
        check("⑨ 更新人字段已写入",
                after != null && "u-editor".equals(after.getUpdateId()) && after.getUpdateTime() != null,
                after == null ? "无行" : ("updateId=" + after.getUpdateId()));

        // ---------- 4. 既有子表关联仍指向它（"不再失联"） ----------
        check("⑩ 附件配置挂在同一个 template_id 上（关联未失联）",
                attachmentMapper.countByTemplateId(TPL_ID) == 1,
                "行数=" + attachmentMapper.countByTemplateId(TPL_ID));
        TemplateDTO reloaded = service.getTemplateDTOById(TPL_ID);
        check("⑪ 按同一个 id 能读回子表配置",
                reloaded != null && reloaded.getAttachment() != null
                        && "附件A".equals(reloaded.getAttachment().getName()),
                reloaded == null || reloaded.getAttachment() == null
                        ? "读不到附件配置" : ("附件名=" + reloaded.getAttachment().getName()));

        // ---------- 5. 再编辑两次：配置表不能堆出第二行 ----------
        dto.setName("新名称2");
        service.updateTemplate(dto);
        dto.setName("新名称3");
        service.updateTemplate(dto);
        check("⑫ 连续 3 次编辑后模板表仍只有 1 行", templateMapper.rows.size() == 1,
                "行数=" + templateMapper.rows.size());
        check("⑬ 连续 3 次编辑后附件配置仍只有 1 行（多行会让读取器抛 TooManyResultsException）",
                attachmentMapper.countByTemplateId(TPL_ID) == 1,
                "行数=" + attachmentMapper.countByTemplateId(TPL_ID));
        check("⑭ 三次编辑都走了原地 UPDATE，没有一次 insert",
                templateMapper.updateCount == 3 && templateMapper.insertCount == 0,
                "update=" + templateMapper.updateCount + ", insert=" + templateMapper.insertCount);

        // ---------- 6. 结论 ----------
        System.out.println("──────────────────────────────────────────────");
        System.out.println("B1 §1.5 updateTemplate 原地更新语义自检");
        System.out.println("通过 " + passed + " 项，失败 " + failures.size() + " 项");
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        System.out.println(failures.isEmpty() ? "结论：PASS" : "结论：FAIL");
        System.out.println("──────────────────────────────────────────────");
        SecurityContextHolder.clearContext();
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failures.add(name + " —— " + detail);
            System.out.println("  ✗ " + name + " —— " + detail);
        }
    }

    /** 把桩塞进 private @Autowired 字段（不引入 Spring 上下文） */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ======================= 内存桩 Mapper =======================

    static class StubTemplateMapper implements TemplateMapper {
        final Map<String, Template> rows = new LinkedHashMap<String, Template>();
        int insertCount = 0;
        int updateCount = 0;

        @Override
        public Template selectTemplateById(String id) {
            return rows.get(id);
        }

        @Override
        public List<Template> selectTemplateList(Template template) {
            return new ArrayList<Template>(rows.values());
        }

        @Override
        public int insertTemplate(Template template) {
            insertCount++;
            rows.put(template.getId(), template);
            return 1;
        }

        @Override
        public int updateTemplate(Template template) {
            updateCount++;
            rows.put(template.getId(), template);
            return 1;
        }

        @Override
        public int deleteTemplateById(String id) {
            rows.remove(id);
            return 1;
        }

        @Override
        public int deleteTemplateByIds(String[] ids) {
            for (String id : ids) {
                rows.remove(id);
            }
            return ids.length;
        }

        @Override
        public int changeEnableFlag(Template template) {
            return 1;
        }

        @Override
        public List<Template> selectNewStartTemplateList(Template template) {
            return new ArrayList<Template>(rows.values());
        }

        @Override
        public String selectDeptAncestors(String deptId) {
            return null;
        }

        @Override
        public String selectTemplateIdBySimpleFlowId(String simpleFlowId) {
            return null;
        }
    }

    static class StubAttachmentMapper implements TemplateAttachmentMapper {
        final Map<String, TemplateAttachment> rows = new LinkedHashMap<String, TemplateAttachment>();

        int countByTemplateId(String templateId) {
            int n = 0;
            for (TemplateAttachment a : rows.values()) {
                if (templateId.equals(a.getTemplateId())) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public TemplateAttachment selectTemplateAttachmentById(String id) {
            return rows.get(id);
        }

        @Override
        public TemplateAttachment selectTemplateAttachmentByTemplateId(String templateId) {
            for (TemplateAttachment a : rows.values()) {
                if (templateId.equals(a.getTemplateId())) {
                    return a;
                }
            }
            return null;
        }

        @Override
        public List<TemplateAttachment> selectTemplateAttachmentList(TemplateAttachment templateAttachment) {
            return new ArrayList<TemplateAttachment>(rows.values());
        }

        @Override
        public int insertTemplateAttachment(TemplateAttachment templateAttachment) {
            rows.put(templateAttachment.getId(), templateAttachment);
            return 1;
        }

        @Override
        public int updateTemplateAttachment(TemplateAttachment templateAttachment) {
            rows.put(templateAttachment.getId(), templateAttachment);
            return 1;
        }

        @Override
        public int deleteTemplateAttachmentById(String id) {
            rows.remove(id);
            return 1;
        }

        @Override
        public int deleteTemplateAttachmentByIds(String[] ids) {
            for (String id : ids) {
                rows.remove(id);
            }
            return ids.length;
        }

        @Override
        public void deleteAttachmentByTemplateId(String templateId) {
            // 注意：必须按 **Map 的键** 收集再删。桩里存的是实体引用，而 TemplateServiceImpl
            // 会在同一实例上改 id，按 a.getId() 删会删不掉（真实 SQL 的
            // `delete ... where template_id = #{templateId}` 不受这个别名问题影响）。
            List<String> hit = new ArrayList<String>();
            for (Map.Entry<String, TemplateAttachment> e : rows.entrySet()) {
                if (templateId.equals(e.getValue().getTemplateId())) {
                    hit.add(e.getKey());
                }
            }
            for (String key : hit) {
                rows.remove(key);
            }
        }

        @Override
        public void deleteAttachmentByTemplateIds(String[] templateIds) {
            for (String templateId : templateIds) {
                deleteAttachmentByTemplateId(templateId);
            }
        }
    }

    static class StubMainTextMapper implements TemplateMainTextMapper {
        final Map<String, TemplateMainText> rows = new LinkedHashMap<String, TemplateMainText>();

        @Override
        public TemplateMainText selectTemplateMainTextById(String id) {
            return rows.get(id);
        }

        @Override
        public TemplateMainText selectByTemplateId(String templateId) {
            for (TemplateMainText t : rows.values()) {
                if (templateId.equals(t.getTemplateId())) {
                    return t;
                }
            }
            return null;
        }

        @Override
        public List<TemplateMainText> selectTemplateMainTextList(TemplateMainText templateMainText) {
            return new ArrayList<TemplateMainText>(rows.values());
        }

        @Override
        public int insertTemplateMainText(TemplateMainText templateMainText) {
            rows.put(templateMainText.getId(), templateMainText);
            return 1;
        }

        @Override
        public int updateTemplateMainText(TemplateMainText templateMainText) {
            rows.put(templateMainText.getId(), templateMainText);
            return 1;
        }

        @Override
        public int deleteTemplateMainTextById(String id) {
            rows.remove(id);
            return 1;
        }

        @Override
        public int deleteTemplateMainTextByIds(String[] ids) {
            for (String id : ids) {
                rows.remove(id);
            }
            return ids.length;
        }

        @Override
        public int deleteMainTextByTemplateId(String templateId) {
            List<String> hit = new ArrayList<String>();
            for (Map.Entry<String, TemplateMainText> e : rows.entrySet()) {
                if (templateId.equals(e.getValue().getTemplateId())) {
                    hit.add(e.getKey());
                }
            }
            for (String key : hit) {
                rows.remove(key);
            }
            return hit.size();
        }
    }

    static class StubMessageNoticeMapper implements TemplateMessageNoticeMapper {        final Map<String, TemplateMessageNotice> rows = new LinkedHashMap<String, TemplateMessageNotice>();

        @Override
        public TemplateMessageNotice selectTemplateMessageNoticeById(String id) {
            return rows.get(id);
        }

        @Override
        public List<TemplateMessageNotice> selectTemplateMessageNoticeList(TemplateMessageNotice templateMessageNotice) {
            return new ArrayList<TemplateMessageNotice>(rows.values());
        }

        @Override
        public int insertTemplateMessageNotice(TemplateMessageNotice templateMessageNotice) {
            rows.put(templateMessageNotice.getId(), templateMessageNotice);
            return 1;
        }

        @Override
        public int updateTemplateMessageNotice(TemplateMessageNotice templateMessageNotice) {
            rows.put(templateMessageNotice.getId(), templateMessageNotice);
            return 1;
        }

        @Override
        public int deleteTemplateMessageNoticeById(String id) {
            rows.remove(id);
            return 1;
        }

        @Override
        public int deleteTemplateMessageNoticeByIds(String[] ids) {
            for (String id : ids) {
                rows.remove(id);
            }
            return ids.length;
        }

        @Override
        public TemplateMessageNotice selectByTemplateId(String templateId) {
            for (TemplateMessageNotice t : rows.values()) {
                if (templateId.equals(t.getTemplateId())) {
                    return t;
                }
            }
            return null;
        }

        @Override
        public int deleteMessageNoticeByTemplateId(String templateId) {
            List<String> hit = new ArrayList<String>();
            for (Map.Entry<String, TemplateMessageNotice> e : rows.entrySet()) {
                if (templateId.equals(e.getValue().getTemplateId())) {
                    hit.add(e.getKey());
                }
            }
            for (String key : hit) {
                rows.remove(key);
            }
            return hit.size();
        }
    }

    /** 2.0 B1 §3.1：可发起范围明细桩（本自检只用它的 delete，不需要真实存储） */
    static class StubSubmitScopeMapper implements TemplateSubmitScopeMapper {
        final List<TemplateSubmitScope> rows = new ArrayList<TemplateSubmitScope>();

        @Override
        public List<TemplateSubmitScope> selectByTemplateId(String templateId) {
            List<TemplateSubmitScope> hit = new ArrayList<TemplateSubmitScope>();
            for (TemplateSubmitScope s : rows) {
                if (templateId.equals(s.getTemplateId())) {
                    hit.add(s);
                }
            }
            return hit;
        }

        @Override
        public List<TemplateSubmitScope> selectByTemplateIds(List<String> templateIds) {
            return new ArrayList<TemplateSubmitScope>(rows);
        }

        @Override
        public int countByTemplateId(String templateId) {
            return selectByTemplateId(templateId).size();
        }

        @Override
        public int deleteByTemplateId(String templateId) {
            List<TemplateSubmitScope> keep = new ArrayList<TemplateSubmitScope>();
            int removed = 0;
            for (TemplateSubmitScope s : rows) {
                if (templateId.equals(s.getTemplateId())) {
                    removed++;
                } else {
                    keep.add(s);
                }
            }
            rows.clear();
            rows.addAll(keep);
            return removed;
        }

        @Override
        public int batchInsert(List<TemplateSubmitScope> list) {
            rows.addAll(list);
            return list.size();
        }
    }

    /** 2.0 B1 §3.1：流程管理员桩 */
    static class StubFlowAdminMapper implements TemplateFlowAdminMapper {
        final List<TemplateFlowAdmin> rows = new ArrayList<TemplateFlowAdmin>();

        @Override
        public List<TemplateFlowAdmin> selectByTemplateId(String templateId) {
            List<TemplateFlowAdmin> hit = new ArrayList<TemplateFlowAdmin>();
            for (TemplateFlowAdmin a : rows) {
                if (templateId.equals(a.getTemplateId())) {
                    hit.add(a);
                }
            }
            return hit;
        }

        @Override
        public int countByTemplateId(String templateId) {
            return selectByTemplateId(templateId).size();
        }

        @Override
        public int countByTemplateIdAndUserId(String templateId, String userId) {
            int n = 0;
            for (TemplateFlowAdmin a : rows) {
                if (templateId.equals(a.getTemplateId()) && userId.equals(a.getUserId())) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public int deleteByTemplateId(String templateId) {
            List<TemplateFlowAdmin> keep = new ArrayList<TemplateFlowAdmin>();
            int removed = 0;
            for (TemplateFlowAdmin a : rows) {
                if (templateId.equals(a.getTemplateId())) {
                    removed++;
                } else {
                    keep.add(a);
                }
            }
            rows.clear();
            rows.addAll(keep);
            return removed;
        }

        @Override
        public int batchInsert(List<TemplateFlowAdmin> list) {
            rows.addAll(list);
            return list.size();
        }
    }
}
