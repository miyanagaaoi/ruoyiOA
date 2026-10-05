package com.ruoyi.ctms.service.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsAttachment;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.mapper.CtmsAttachmentMapper;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.support.CtmsAttachmentObjectTypes;
import com.ruoyi.ctms.support.CtmsAttachmentRules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link CtmsAttachmentServiceImpl} 的业务口径单测（2.0 B3 任务 6.1~6.3）。 </p>
 *
 * <p> <b>不依赖 Spring / 不依赖数据库 / 不依赖真实 HTTP</b>：Mapper 与服务都用内存桩，
 * 通过反射注入 {@code @Autowired} 字段；上传路径指向 JUnit 的临时目录
 * （{@code RuoYiConfig.profile} 是静态字段，测试类自己 set/restore），
 * 因此"落盘了没有 / 半成品删了没有"这类断言是<b>真实文件系统</b>上的断言。 </p>
 *
 * <p> 覆盖（对应 tasks.md §6.1~6.3 的「验证：」）：
 * <ul>
 *   <li>6.1 合法对象上传成功并落库 / 未注册对象类型被拒 / 对象不存在被拒；</li>
 *   <li>6.2 白名单外后缀被拒 / 20MB 边界（等于通过、超出 1 字节被拒）/
 *       超限后半成品文件不存在 / 范围外对象 403；</li>
 *   <li>6.3 删除附件写字段名 {@code 附件} 的变更历史且含操作人 / 删除后取不到（= 下载接口不再返回）。</li>
 * </ul>
 *
 * @author 二开
 */
public class CtmsAttachmentServiceImplTest
{
    /** 夹具合同ID（32 位十六进制形态，与真实主键同形） */
    private static final String CONTRACT_ID = "ATTC0000000000000000000000000001";

    /** 一个"存在但不在当前用户范围内"的合同ID */
    private static final String FOREIGN_CONTRACT_ID = "ATTC0000000000000000000000000002";

    private static Path profileRoot;

    private static String originalProfile;

    private final StubAttachmentMapper attachmentMapper = new StubAttachmentMapper();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final StubContractService contractService = new StubContractService();

    private final CtmsAttachmentServiceImpl service = new TestAttachmentService();

    @BeforeClass
    public static void prepareProfile() throws Exception
    {
        profileRoot = Files.createTempDirectory("ctms-attachment-test");
        originalProfile = RuoYiConfig.getProfile();
        setProfile(profileRoot.toString());
    }

    @AfterClass
    public static void restoreProfile() throws Exception
    {
        setProfile(originalProfile);
        deleteRecursively(profileRoot.toFile());
    }

    @Before
    public void setUp()
    {
        // 每个用例从空目录开始：落盘断言（"半成品不存在"）只有在目录干净时才有判别力
        cleanDirectory(profileRoot.toFile());
        attachmentMapper.store.clear();
        changeLogMapper.store.clear();
        contractService.existing.clear();
        contractService.accessible.clear();
        contractService.existing.add(CONTRACT_ID);
        contractService.existing.add(FOREIGN_CONTRACT_ID);
        contractService.accessible.add(CONTRACT_ID);
        inject(service, "attachmentMapper", attachmentMapper);
        inject(service, "contractService", contractService);
        inject(service, "changeLogMapper", changeLogMapper);
    }

    /* ==================== 6.1 对象挂载层 ==================== */

    @Test
    public void 合法对象上传成功并落库且文件真的写在磁盘上()
    {
        byte[] content = "B3 attachment fixture".getBytes();
        CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("合同扫描件.pdf", content, "application/pdf"));

        assertNotNull("上传应返回落库后的元数据", saved);
        assertNotNull("主键应由服务层生成", saved.getId());
        assertEquals(CtmsAttachmentObjectTypes.CONTRACT, saved.getObjectType());
        assertEquals(CONTRACT_ID, saved.getObjectId());
        assertEquals("合同侧对象的 contract_id 与 object_id 同值", CONTRACT_ID, saved.getContractId());
        assertEquals("合同扫描件.pdf", saved.getFileName());
        assertEquals("application/pdf", saved.getContentType());
        assertEquals(Long.valueOf(content.length), saved.getSizeBytes());
        assertEquals("未删除", CtmsAttachment.DEL_FLAG_NORMAL, saved.getDelFlag());

        // ① 元数据真的进了"库"
        assertEquals(1, attachmentMapper.store.size());
        // ② 物理文件真的落盘了（路径 = profile + storedPath 去掉 /profile 前缀）
        assertTrue("storedPath 应为 /profile 开头的相对路径："
                + saved.getStoredPath(), saved.getStoredPath().startsWith(Constants.RESOURCE_PREFIX + "/"));
        File stored = new File(profileRoot + saved.getStoredPath().substring(Constants.RESOURCE_PREFIX.length()));
        assertTrue("物理文件应存在于 " + stored.getAbsolutePath(), stored.isFile());
        assertEquals("文件字节应与上传内容一致", content.length, stored.length());
        // ③ 随机命名：不叫原名（日期目录 + 原名 + 序列 + 后缀）
        assertFalse("存储名应与原名不同（随机命名）",
                stored.getName().equals(saved.getFileName()));
        assertTrue("存储名应保留后缀", stored.getName().endsWith(".pdf"));
    }

    @Test
    public void 未注册的对象类型被拒且不落库不落盘()
    {
        assertRejected("未注册的对象类型", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment("ctms_unknown_object", CONTRACT_ID,
                        new FakeMultipartFile("x.pdf", "x".getBytes(), "application/pdf"));
            }
        });
        assertEquals("被拒时不得落库", 0, attachmentMapper.store.size());
        assertEquals("被拒时不得落盘", 0, countFiles(profileRoot.toFile()));
    }

    @Test
    public void 对象类型为空被拒()
    {
        assertRejected("对象类型不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment("  ", CONTRACT_ID,
                        new FakeMultipartFile("x.pdf", "x".getBytes(), "application/pdf"));
            }
        });
    }

    @Test
    public void 对象不存在被拒()
    {
        assertRejected("合同不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT,
                        "ATTC00000000000000000000000000FF",
                        new FakeMultipartFile("x.pdf", "x".getBytes(), "application/pdf"));
            }
        });
        assertEquals("被拒时不得落库", 0, attachmentMapper.store.size());
    }

    @Test
    public void 范围外的对象上传被拒为业务码403()
    {
        try
        {
            service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, FOREIGN_CONTRACT_ID,
                    new FakeMultipartFile("x.pdf", "x".getBytes(), "application/pdf"));
            fail("范围外对象应被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("范围外应返回业务码 403", Integer.valueOf(403), e.getCode());
            assertTrue(e.getMessage().contains("无权访问该合同"));
        }
        assertEquals("被拒时不得落库", 0, attachmentMapper.store.size());
        assertEquals("被拒时不得落盘", 0, countFiles(profileRoot.toFile()));
    }

    @Test
    public void 对象标识为空被拒()
    {
        assertRejected("对象标识不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.selectAttachmentList(CtmsAttachmentObjectTypes.CONTRACT, "");
            }
        });
    }

    /* ==================== 6.2 白名单 / 大小 / 半成品 ==================== */

    @Test
    public void 白名单外后缀被拒且文案与平台同款()
    {
        assertRejected("后缀[zip]不正确", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                        new FakeMultipartFile("打包.zip", "PK".getBytes(), "application/zip"));
            }
        });
        // 平台白名单里有 zip，但我们更窄 —— 这条正是"不能依赖平台白名单"的证据
        assertRejected("后缀[mp4]不正确", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                        new FakeMultipartFile("视频.mp4", "xx".getBytes(), "video/mp4"));
            }
        });
        assertEquals(0, attachmentMapper.store.size());
        assertEquals("被拒时不得落盘", 0, countFiles(profileRoot.toFile()));
    }

    @Test
    public void 白名单内的十种后缀全部放行()
    {
        for (String extension : CtmsAttachmentRules.ALLOWED_EXTENSIONS)
        {
            CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                    new FakeMultipartFile("白名单." + extension, "x".getBytes(), "application/octet-stream"));
            assertEquals("后缀 " + extension + " 应被放行", "白名单." + extension, saved.getFileName());
        }
        assertEquals(CtmsAttachmentRules.ALLOWED_EXTENSIONS.size(), attachmentMapper.store.size());
    }

    @Test
    public void 无后缀文件名被拒()
    {
        assertRejected("必须带后缀名", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                        new FakeMultipartFile("README", "x".getBytes(), "text/plain"));
            }
        });
    }

    @Test
    public void 二十兆边界等于上限通过超出上限被拒且不留半成品()
    {
        // ① 恰好等于 20MB → 放行（任务 6.2 明确要求"等于与超出各一次"）
        byte[] exact = new byte[(int) CtmsAttachmentRules.MAX_SIZE_BYTES];
        CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("边界.pdf", exact, "application/pdf"));
        assertEquals("等于 20MB 应放行", Long.valueOf(CtmsAttachmentRules.MAX_SIZE_BYTES), saved.getSizeBytes());
        int filesAfterExact = countFiles(profileRoot.toFile());
        assertEquals("等于 20MB 的文件应真的落盘", 1, filesAfterExact);

        // ② 超过 1 字节 → 413 拒绝，且不新增任何文件
        byte[] over = new byte[(int) CtmsAttachmentRules.MAX_SIZE_BYTES + 1];
        try
        {
            service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                    new FakeMultipartFile("超限.pdf", over, "application/pdf"));
            fail("超过 20MB 应被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("超限应返回业务码 413", Integer.valueOf(413), e.getCode());
            assertTrue("文案应含上限： " + e.getMessage(), e.getMessage().contains("20MB"));
        }
        assertEquals("超限的文件不得落盘（半成品清理）", filesAfterExact, countFiles(profileRoot.toFile()));
        assertEquals("超限的元数据不得落库", 1, attachmentMapper.store.size());
    }

    @Test
    public void 空文件被拒()
    {
        assertRejected("上传文件不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                        new FakeMultipartFile("空.pdf", new byte[0], "application/pdf"));
            }
        });
    }

    @Test
    public void 中文文件名的存储名保留原名且不会退化成只有后缀()
    {
        CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("合同扫描件.pdf", "pdf".getBytes(), "application/pdf"));

        String stored = saved.getStoredPath();
        assertTrue("存储路径应以日期目录开头：" + stored, stored.matches(
                "/profile/upload/\\d{4}/\\d{2}/\\d{2}/.+"));
        String storedName = stored.substring(stored.lastIndexOf('/') + 1);
        assertTrue("存储名应保留中文主名（这条是真实环境踩过的坑）：" + storedName,
                storedName.startsWith("合同扫描件_"));
        assertTrue("存储名应保留后缀：" + storedName, storedName.endsWith(".pdf"));
        // 同名两次上传得到不同存储名（不覆盖同名文件）
        CtmsAttachment again = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("合同扫描件.pdf", "pdf".getBytes(), "application/pdf"));
        assertFalse("同名文件重复上传不得覆盖", stored.equals(again.getStoredPath()));
    }

    /* ==================== 6.3 删除留痕 ==================== */

    @Test
    public void 删除附件写字段名为附件的变更历史且含操作人()
    {
        CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("待删附件.docx", "doc".getBytes(),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        service.deleteAttachment(saved.getId());

        // ① 元数据软删除
        CtmsAttachment row = attachmentMapper.store.get(saved.getId());
        assertEquals("应置为已删除", CtmsAttachment.DEL_FLAG_DELETED, row.getDelFlag());
        assertEquals("应记录更新人", "100", row.getUpdateId());

        // ② 变更历史：字段名「附件」、含操作人、旧值是文件名、新值为空
        assertEquals("应恰好写一条变更历史", 1, changeLogMapper.store.size());
        CtmsChangeLog log = changeLogMapper.store.get(0);
        assertEquals("附件", log.getFieldName());
        assertEquals("操作人标识应落库", "100", log.getOperatorId());
        assertEquals("操作人姓名快照应落库", "张三", log.getOperatorName());
        assertTrue("旧值应含被删文件名：" + log.getOldValue(),
                log.getOldValue() != null && log.getOldValue().contains("待删附件.docx"));
        assertNull("新值应为空（表示该附件被移除）", log.getNewValue());
        assertEquals("变更历史应挂在所属合同上", CONTRACT_ID, log.getContractId());
        assertEquals("source 应为 manual（是人删的）", CtmsChangeLog.SOURCE_MANUAL, log.getSource());
    }

    @Test
    public void 删除后按标识取不到即下载接口不再返回该文件()
    {
        CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("会被删.pdf", "pdf".getBytes(), "application/pdf"));
        service.deleteAttachment(saved.getId());

        // 列表里看不到
        assertTrue(service.selectAttachmentList(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID).isEmpty());
        // 按标识也取不到（下载接口走的就是这个方法）
        assertRejected("附件不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.requireDownloadable(saved.getId());
            }
        });
        // 物理文件保留（审计口径：软删除不动磁盘）
        assertEquals(1, countFiles(profileRoot.toFile()));
    }

    @Test
    public void 重复删除第二次被拒()
    {
        CtmsAttachment saved = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("删两次.pdf", "pdf".getBytes(), "application/pdf"));
        service.deleteAttachment(saved.getId());
        assertRejected("附件不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.deleteAttachment(saved.getId());
            }
        });
        assertEquals("重复删除不得写第二条变更历史", 1, changeLogMapper.store.size());
    }

    /* ==================== 下载与列表 ==================== */

    @Test
    public void 下载前先鉴权范围外403()
    {
        // 直接 SQL 造一条"属于别人合同"的附件夹具
        CtmsAttachment foreign = new CtmsAttachment();
        foreign.setId("ATTA00000000000000000000000000FF");
        foreign.setObjectType(CtmsAttachmentObjectTypes.CONTRACT);
        foreign.setObjectId(FOREIGN_CONTRACT_ID);
        foreign.setContractId(FOREIGN_CONTRACT_ID);
        foreign.setFileName("别人的.pdf");
        foreign.setStoredPath("/profile/upload/2026/10/05/foreign.pdf");
        foreign.setSizeBytes(10L);
        foreign.setDelFlag(CtmsAttachment.DEL_FLAG_NORMAL);
        attachmentMapper.store.put(foreign.getId(), foreign);

        try
        {
            service.requireDownloadable(foreign.getId());
            fail("范围外的附件应返回 403");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(403), e.getCode());
        }
    }

    @Test
    public void 列表按上传时间倒序返回且只含未删除()
    {
        CtmsAttachment first = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("第一个.pdf", "1".getBytes(), "application/pdf"));
        CtmsAttachment second = service.uploadAttachment(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID,
                new FakeMultipartFile("第二个.pdf", "2".getBytes(), "application/pdf"));
        service.deleteAttachment(first.getId());

        List<CtmsAttachment> list = service.selectAttachmentList(CtmsAttachmentObjectTypes.CONTRACT, CONTRACT_ID);
        assertEquals("只返回未删除的一条", 1, list.size());
        assertEquals(second.getId(), list.get(0).getId());
    }

    @Test
    public void 对象类型大小写不敏感且归一为小写()
    {
        CtmsAttachment saved = service.uploadAttachment("Contract", CONTRACT_ID,
                new FakeMultipartFile("大写类型.pdf", "x".getBytes(), "application/pdf"));
        assertEquals("对象类型应归一为小写", CtmsAttachmentObjectTypes.CONTRACT, saved.getObjectType());
        assertTrue(service.isRegisteredObjectType("CONTRACT"));
        assertFalse(service.isRegisteredObjectType("purchase_order"));
    }

    /* ==================== 桩与小工具 ==================== */

    private static void inject(Object target, String name, Object value)
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
            catch (Exception e)
            {
                throw new IllegalStateException("注入字段失败：" + name, e);
            }
        }
        throw new IllegalStateException("找不到可注入的字段：" + name);
    }

    private static void setProfile(String value) throws Exception
    {
        Field field = RuoYiConfig.class.getDeclaredField("profile");
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void assertRejected(String expected, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒绝并提示：" + expected);
        }
        catch (ServiceException e)
        {
            String message = e.getMessage();
            assertTrue("提示应包含「" + expected + "」，实际为「" + message + "」",
                    message != null && message.contains(expected));
        }
    }

    private static int countFiles(File dir)
    {
        File[] children = dir.listFiles();
        if (children == null)
        {
            return 0;
        }
        int count = 0;
        for (File child : children)
        {
            count += child.isDirectory() ? countFiles(child) : 1;
        }
        return count;
    }

    private static void deleteRecursively(File file)
    {
        if (file == null || !file.exists())
        {
            return;
        }
        File[] children = file.listFiles();
        if (children != null)
        {
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }
        // 临时目录清不掉不影响用例结论
        if (!file.delete())
        {
            file.deleteOnExit();
        }
    }

    /** 清空目录内容但保留目录本身（每个用例的落盘断言依赖"起始为空"）。 */
    private static void cleanDirectory(File dir)
    {
        File[] children = dir.listFiles();
        if (children == null)
        {
            return;
        }
        for (File child : children)
        {
            deleteRecursively(child);
        }
    }

    /**
     * 把"当前用户"换成固定值的服务子类（生产实现里这三个点都是 protected 方法）。
     *
     * <p> 不这么做的话，没有 Spring Security 上下文时 {@code SecurityUtils.getUserId()} 会抛异常、
     * 被生产实现的兜底逻辑换成 {@code "1"}，于是"操作人是谁"这类断言就没有判别力了。 </p>
     */
    private static class TestAttachmentService extends CtmsAttachmentServiceImpl
    {
        @Override
        protected String currentUserId()
        {
            return "100";
        }

        @Override
        protected String currentUsername()
        {
            return "zhangsan";
        }

        @Override
        protected String currentNickName()
        {
            return "张三";
        }
    }

    /** 最小 MultipartFile 桩：只实现服务层真正用到的方法。 */
    private static class FakeMultipartFile implements MultipartFile
    {
        private final String name;

        private final byte[] content;

        private final String contentType;

        FakeMultipartFile(String name, byte[] content, String contentType)
        {
            this.name = name;
            this.content = content;
            this.contentType = contentType;
        }

        @Override
        public String getName()
        {
            return "file";
        }

        @Override
        public String getOriginalFilename()
        {
            return name;
        }

        @Override
        public String getContentType()
        {
            return contentType;
        }

        @Override
        public boolean isEmpty()
        {
            return content.length == 0;
        }

        @Override
        public long getSize()
        {
            return content.length;
        }

        @Override
        public byte[] getBytes() throws IOException
        {
            return content;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException
        {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists())
            {
                parent.mkdirs();
            }
            Files.write(dest.toPath(), content);
        }
    }

    /** 附件桩：只按 del_flag 过滤，模拟 XML 里的 {@code del_flag='0'}。 */
    private static class StubAttachmentMapper implements CtmsAttachmentMapper
    {
        private final Map<String, CtmsAttachment> store = new LinkedHashMap<>();

        @Override
        public List<CtmsAttachment> selectAttachmentList(String objectType, String objectId,
                                                        String contractDataScopeSql)
        {
            List<CtmsAttachment> result = new ArrayList<>();
            for (CtmsAttachment row : store.values())
            {
                if ("0".equals(row.getDelFlag())
                        && objectType.equals(row.getObjectType())
                        && objectId.equals(row.getObjectId()))
                {
                    result.add(row);
                }
            }
            return result;
        }

        @Override
        public CtmsAttachment selectAttachmentById(String id)
        {
            CtmsAttachment row = store.get(id);
            return (row != null && "0".equals(row.getDelFlag())) ? row : null;
        }

        @Override
        public int insertAttachment(CtmsAttachment attachment)
        {
            store.put(attachment.getId(), attachment);
            return 1;
        }

        @Override
        public int softDeleteAttachment(String id, String updateId, String updateBy)
        {
            CtmsAttachment row = store.get(id);
            if (row == null || !"0".equals(row.getDelFlag()))
            {
                return 0;
            }
            row.setDelFlag(CtmsAttachment.DEL_FLAG_DELETED);
            row.setUpdateId(updateId);
            row.setUpdateBy(updateBy);
            return 1;
        }

        @Override
        public int countByObject(String objectType, String objectId)
        {
            return selectAttachmentList(objectType, objectId, null).size();
        }
    }

    /** 变更历史桩：只追加。 */
    private static class StubChangeLogMapper implements CtmsChangeLogMapper
    {
        private final List<CtmsChangeLog> store = new ArrayList<>();

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            return new ArrayList<>(store);
        }

        @Override
        public int batchInsertChangeLogs(List<CtmsChangeLog> logs)
        {
            store.addAll(logs);
            return logs.size();
        }

        @Override
        public int insertChangeLog(CtmsChangeLog log)
        {
            store.add(log);
            return 1;
        }
    }

    /**
     * 合同服务桩：把"存在"与"可见"分开维护。
     *
     * <p> 刻意<b>不做</b>数据范围算法（那是 {@code ContractDataScopeTest} 的事），
     * 只验证"附件服务确实把对象存在性与范围判定委托给了合同服务"这一条契约：
     * <b>附件侧不允许自己实现一份范围判定</b>。 </p>
     */
    private static class StubContractService implements ICtmsContractService
    {
        /** 库里存在的合同 */
        private final List<String> existing = new ArrayList<>();

        /** 当前用户范围内的合同 */
        private final List<String> accessible = new ArrayList<>();

        @Override
        public void checkContractAccess(String id)
        {
            if (!existing.contains(id))
            {
                throw new ServiceException("合同不存在");
            }
            if (!accessible.contains(id))
            {
                throw new ServiceException("无权访问该合同", Integer.valueOf(403));
            }
        }

        @Override
        public List<CtmsContract> selectContractList(CtmsContract query)
        {
            return new ArrayList<>();
        }

        @Override
        public CtmsContract selectContractDetail(String id)
        {
            return null;
        }

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            return new ArrayList<>();
        }

        @Override
        public void insertContract(CtmsContract contract)
        {
        }

        @Override
        public void updateContract(CtmsContract contract)
        {
        }

        @Override
        public int changeStatus(CtmsContract contract)
        {
            return 0;
        }

        @Override
        public void softDelete(String id, String reason)
        {
        }

        @Override
        public void restore(String id)
        {
        }

        @Override
        public int releaseWarranty(String id)
        {
            return 0;
        }

        @Override
        public CtmsContract selectFrameworkDetail(String id)
        {
            return null;
        }

        @Override
        public void deleteContract(String id)
        {
        }

        @Override
        public String previewContractNo(String type, String subjectCode, String referenceDay)
        {
            return null;
        }

        @Override
        public com.ruoyi.ctms.domain.vo.CtmsWarrantyReminderVo selectWarrantyReminders()
        {
            return null;
        }
    }
}
