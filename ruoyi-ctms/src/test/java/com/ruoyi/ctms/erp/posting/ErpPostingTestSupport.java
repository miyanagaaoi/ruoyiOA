package com.ruoyi.ctms.erp.posting;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.dao.DuplicateKeyException;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.base.service.IErpDocObjectAccess;
import com.ruoyi.ctms.erp.posting.domain.ErpStock;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockInItemMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockInMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockLedgerMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockOutItemMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockOutMapper;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>过账/单据单测的内存桩</b>（2.0 B4 任务 5.2~5.6；照 B3 的"不起 Spring"风格）。 </p>
 *
 * <p> 关键点是 {@link StubStockMapper} 对<b>行锁</b>的模拟： </p>
 * <ul>
 *   <li> {@code selectStockForUpdate} 按（物料, 仓库）取得 {@link ReentrantLock}
 *        并把该 key 记进当前线程的持有列表； </li>
 *   <li> {@code updateStockQty} 写入数量并<b>释放</b>该 key 的锁 ——
 *        于是"锁的持有时段" = 服务里的"读值 → 写值"这一区间，与 InnoDB 行锁的
 *        并发语义一致； </li>
 *   <li> {@code insertStock} 撞已存在的（物料, 仓库）时抛 {@link DuplicateKeyException}，
 *        与真实唯一索引 {@code uk_stock_product_warehouse} 的 1062 同形态，
 *        用于覆盖 D3 的"重复键重试"分支。 </li>
 * </ul>
 *
 * <p> <b>与真实库的差别（必须在 notes 里写明）</b>：内存桩没有事务回滚。
 * 因此"某一行库存不足 → 整单不产生任何流水"这条断言依赖服务实现的<b>两阶段</b>写法
 * （阶段一只校验不写库），而不是依赖数据库回滚；真实库的整单回滚由 T10/T13 的
 * 接口级并发用例覆盖。 </p>
 *
 * @author 二开
 */
public final class ErpPostingTestSupport
{
    private ErpPostingTestSupport()
    {
    }

    /**
     * 反射注入 {@code @Autowired} 私有字段（B3 单测同款做法）。
     *
     * @param target    目标对象
     * @param fieldName 字段名
     * @param value     值
     */
    public static void inject(Object target, String fieldName, Object value)
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException("注入失败：" + fieldName, e);
            }
        }
        throw new IllegalStateException("字段不存在：" + fieldName);
    }

    /**
     * 读私有/受保护字段（断言用）。
     *
     * @param target    目标对象
     * @param fieldName 字段名
     * @return 字段值
     */
    public static Object read(Object target, String fieldName)
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException("读取失败：" + fieldName, e);
            }
        }
        throw new IllegalStateException("字段不存在：" + fieldName);
    }

    /**
     * 结存桩（带行锁模拟，见类注释）。
     */
    public static class StubStockMapper implements ErpStockMapper
    {
        /** (物料 \u0001 仓库) → 结存行。 */
        private final Map<String, ErpStock> rows = new ConcurrentHashMap<>();

        /** key → 行锁。 */
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        /** 当前线程持有的 key（模拟事务内持有的行锁）。 */
        private final ThreadLocal<List<String>> held = new ThreadLocal<List<String>>()
        {
            @Override
            protected List<String> initialValue()
            {
                return new ArrayList<>();
            }
        };

        /**
         * 预置一行结存（夹具）。
         *
         * @param productId   物料ID
         * @param warehouseId 仓库ID
         * @param qty         数量
         */
        public void seed(String productId, String warehouseId, BigDecimal qty)
        {
            ErpStock stock = new ErpStock();
            stock.setId("S-" + productId + "-" + warehouseId);
            stock.setProductId(productId);
            stock.setWarehouseId(warehouseId);
            stock.setQty(qty);
            rows.put(key(productId, warehouseId), stock);
        }

        /**
         * 当前结存数量（断言用；无行返回 null）。
         *
         * @param productId   物料ID
         * @param warehouseId 仓库ID
         * @return 数量
         */
        public BigDecimal qty(String productId, String warehouseId)
        {
            ErpStock stock = rows.get(key(productId, warehouseId));
            return stock == null ? null : stock.getQty();
        }

        /**
         * 释放当前线程全部持有锁（模拟事务回滚/提交后的解锁；被拒的过账会留下未释放的锁）。
         */
        public void releaseAllHeldLocks()
        {
            List<String> list = held.get();
            for (String key : new ArrayList<>(list))
            {
                ReentrantLock lock = locks.get(key);
                if (lock != null && lock.isHeldByCurrentThread())
                {
                    lock.unlock();
                }
            }
            list.clear();
        }

        @Override
        public ErpStock selectStockByKey(String productId, String warehouseId)
        {
            return copy(rows.get(key(productId, warehouseId)));
        }

        @Override
        public ErpStock selectStockForUpdate(String productId, String warehouseId)
        {
            String key = key(productId, warehouseId);
            ReentrantLock lock = lockOf(key);
            // 同一线程重复取同一把行锁（过账引擎在"创建结存行"路径上会再读一次）不再叠加持有计数，
            // 否则 updateStockQty 只释放一次会让这把锁永远拿不回来（并发用例会假死）
            if (!lock.isHeldByCurrentThread())
            {
                lock.lock();
            }
            held.get().add(key);
            return copy(rows.get(key));
        }

        @Override
        public List<ErpStock> selectStockList(ErpStock query)
        {
            List<ErpStock> list = new ArrayList<>();
            for (ErpStock stock : rows.values())
            {
                if (query == null || query.getProductId() == null
                        || query.getProductId().equals(stock.getProductId()))
                {
                    list.add(copy(stock));
                }
            }
            return list;
        }

        @Override
        public int insertStock(ErpStock stock)
        {
            ErpStock previous = rows.putIfAbsent(key(stock.getProductId(), stock.getWarehouseId()), copy(stock));
            if (previous != null)
            {
                // 与真实唯一索引 uk_stock_product_warehouse 的 1062 同形态
                throw new DuplicateKeyException("uk_stock_product_warehouse 冲突："
                        + key(stock.getProductId(), stock.getWarehouseId()));
            }
            return 1;
        }

        @Override
        public int updateStockQty(String id, BigDecimal qty)
        {
            for (Map.Entry<String, ErpStock> entry : rows.entrySet())
            {
                if (entry.getValue().getId().equals(id))
                {
                    entry.getValue().setQty(qty);
                    releaseOne(entry.getKey());
                    return 1;
                }
            }
            releaseOne(null);
            return 0;
        }

        @Override
        public int countStock()
        {
            return rows.size();
        }

        private void releaseOne(String key)
        {
            List<String> list = held.get();
            for (int i = list.size() - 1; i >= 0; i--)
            {
                String current = list.get(i);
                if (key == null || current.equals(key))
                {
                    list.remove(i);
                    ReentrantLock lock = locks.get(current);
                    if (lock != null && lock.isHeldByCurrentThread())
                    {
                        lock.unlock();
                    }
                    return;
                }
            }
        }

        private ReentrantLock lockOf(String key)
        {
            ReentrantLock lock = locks.get(key);
            if (lock == null)
            {
                lock = new ReentrantLock();
                ReentrantLock previous = locks.putIfAbsent(key, lock);
                if (previous != null)
                {
                    lock = previous;
                }
            }
            return lock;
        }

        private static String key(String productId, String warehouseId)
        {
            return productId + "\u0001" + warehouseId;
        }

        private static ErpStock copy(ErpStock source)
        {
            if (source == null)
            {
                return null;
            }
            ErpStock target = new ErpStock();
            target.setId(source.getId());
            target.setProductId(source.getProductId());
            target.setWarehouseId(source.getWarehouseId());
            target.setQty(source.getQty());
            target.setCreateTime(source.getCreateTime());
            target.setUpdateTime(source.getUpdateTime());
            return target;
        }
    }

    /**
     * 流水桩（只增不改；查询与累计）。
     */
    public static class StubLedgerMapper implements ErpStockLedgerMapper
    {
        /** 全部流水（插入顺序）。 */
        public final List<ErpStockLedger> all = Collections.synchronizedList(new ArrayList<ErpStockLedger>());

        /**
         * 某（物料, 仓库）的流水条数。
         *
         * @param productId   物料ID
         * @param warehouseId 仓库ID
         * @return 条数
         */
        public int countOf(String productId, String warehouseId)
        {
            int count = 0;
            synchronized (all)
            {
                for (ErpStockLedger ledger : all)
                {
                    if (ledger.getProductId().equals(productId) && ledger.getWarehouseId().equals(warehouseId))
                    {
                        count++;
                    }
                }
            }
            return count;
        }

        @Override
        public int insertLedger(ErpStockLedger ledger)
        {
            if (ledger.getCreateTime() == null)
            {
                ledger.setCreateTime(new Date());
            }
            all.add(copy(ledger));
            return 1;
        }

        @Override
        public List<ErpStockLedger> selectLedgerByDoc(String docType, String docId)
        {
            List<ErpStockLedger> list = new ArrayList<>();
            synchronized (all)
            {
                for (ErpStockLedger ledger : all)
                {
                    if (equals(ledger.getDocType(), docType) && equals(ledger.getDocId(), docId))
                    {
                        list.add(copy(ledger));
                    }
                }
            }
            return list;
        }

        @Override
        public BigDecimal sumQtyChangeByKey(String productId, String warehouseId)
        {
            BigDecimal sum = BigDecimal.ZERO;
            synchronized (all)
            {
                for (ErpStockLedger ledger : all)
                {
                    if (ledger.getProductId().equals(productId) && ledger.getWarehouseId().equals(warehouseId))
                    {
                        sum = sum.add(ledger.getQtyChange());
                    }
                }
            }
            return sum;
        }

        @Override
        public int countLedger()
        {
            return all.size();
        }

        private static boolean equals(String left, String right)
        {
            return left == null ? right == null : left.equals(right);
        }

        private static ErpStockLedger copy(ErpStockLedger source)
        {
            ErpStockLedger target = new ErpStockLedger();
            target.setId(source.getId());
            target.setProductId(source.getProductId());
            target.setWarehouseId(source.getWarehouseId());
            target.setBizType(source.getBizType());
            target.setDocType(source.getDocType());
            target.setDocId(source.getDocId());
            target.setDocNo(source.getDocNo());
            target.setSrcDocNo(source.getSrcDocNo());
            target.setQtyChange(source.getQtyChange());
            target.setQtyAfter(source.getQtyAfter());
            target.setUnitPrice(source.getUnitPrice());
            target.setDeptId(source.getDeptId());
            target.setCreateId(source.getCreateId());
            target.setCreateBy(source.getCreateBy());
            target.setRemark(source.getRemark());
            target.setCreateTime(source.getCreateTime());
            return target;
        }
    }

    /**
     * 变更历史桩（B3 的 {@code t_ctms_change_log}）。
     */
    public static class StubChangeLogMapper implements CtmsChangeLogMapper
    {
        /** 全部日志。 */
        public final List<CtmsChangeLog> all = new ArrayList<>();

        /**
         * 按对象取日志条数。
         *
         * @param objectType 对象类型
         * @param objectId   对象ID
         * @return 条数
         */
        public int countOf(String objectType, String objectId)
        {
            int count = 0;
            for (CtmsChangeLog log : all)
            {
                if (equals(log.getObjectType(), objectType) && equals(log.getObjectId(), objectId))
                {
                    count++;
                }
            }
            return count;
        }

        /**
         * 取某字段的日志条数。
         *
         * @param objectId  对象ID
         * @param fieldName 字段名
         * @return 条数
         */
        public int countOfField(String objectId, String fieldName)
        {
            int count = 0;
            for (CtmsChangeLog log : all)
            {
                if (equals(log.getObjectId(), objectId) && equals(log.getFieldName(), fieldName))
                {
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            List<CtmsChangeLog> list = new ArrayList<>();
            for (CtmsChangeLog log : all)
            {
                if (query == null || (query.getObjectId() == null || equals(query.getObjectId(), log.getObjectId())))
                {
                    list.add(log);
                }
            }
            return list;
        }

        @Override
        public int batchInsertChangeLogs(List<CtmsChangeLog> logs)
        {
            if (logs != null)
            {
                all.addAll(logs);
            }
            return logs == null ? 0 : logs.size();
        }

        @Override
        public int insertChangeLog(CtmsChangeLog log)
        {
            all.add(log);
            return 1;
        }

        private static boolean equals(String left, String right)
        {
            return left == null ? right == null : left.equals(right);
        }
    }

    /**
     * 单据对象访问桩：默认全部放行；可切到"越权"以断言 403。
     */
    public static class StubDocObjectAccess implements IErpDocObjectAccess
    {
        /** 是否拒绝访问（越权场景）。 */
        public boolean forbidden;

        @Override
        public boolean supports(String objectType)
        {
            return true;
        }

        @Override
        public void checkObjectAccess(String objectType, String objectId)
        {
            if (forbidden)
            {
                throw new ServiceException("无权访问该单据", 403);
            }
        }
    }

    /**
     * 主数据桩（物料 / 仓库 / 单位）。
     */
    public static class StubMasterLookup implements ErpMasterGuards.MasterLookup
    {
        private final Map<String, ErpMasterGuards.MasterRecord> products = new LinkedHashMap<>();

        private final Map<String, ErpMasterGuards.MasterRecord> warehouses = new LinkedHashMap<>();

        private final Map<String, ErpMasterGuards.MasterRecord> uoms = new LinkedHashMap<>();

        /**
         * 加一个单位。
         *
         * @param id       单位ID
         * @param name     单位名
         * @param decimals 小数位
         */
        public void addUom(String id, String name, Integer decimals)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, id, name);
            record.setDecimals(decimals);
            record.setEnableFlag(ErpMasterGuards.ENABLE_YES);
            uoms.put(id, record);
        }

        /**
         * 加一个仓库。
         *
         * @param id   仓库ID
         * @param name 仓库名
         */
        public void addWarehouse(String id, String name)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, id, name);
            record.setEnableFlag(ErpMasterGuards.ENABLE_YES);
            warehouses.put(id, record);
        }

        /**
         * 加一个物料。
         *
         * @param id     物料ID
         * @param code   物料编码
         * @param name   物料名
         * @param uomId  单位ID
         */
        public void addProduct(String id, String code, String name, String uomId)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setUomId(uomId);
            record.setEnableFlag(ErpMasterGuards.ENABLE_YES);
            products.put(id, record);
        }

        /**
         * 加一个<b>已停用</b>的物料（t23：盘点差异生成调整单的场景 —— 结存建立后物料被停用）。
         *
         * @param id     物料ID
         * @param code   物料编码
         * @param name   物料名
         * @param uomId  单位ID
         */
        public void addDisabledProduct(String id, String code, String name, String uomId)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setUomId(uomId);
            record.setEnableFlag(ErpMasterGuards.ENABLE_NO);
            products.put(id, record);
        }

        /**
         * 加一个<b>已停用</b>的单位（用于断言"生成路径只豁免物料、不豁免单位"）。
         *
         * @param id       单位ID
         * @param name     单位名
         * @param decimals 小数位
         */
        public void addDisabledUom(String id, String name, Integer decimals)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, id, name);
            record.setDecimals(decimals);
            record.setEnableFlag(ErpMasterGuards.ENABLE_NO);
            uoms.put(id, record);
        }

        /**
         * 加一个物料并把它的计量单位指向一个<b>不存在</b>的单位（断言"单位不存在仍被拦"）。
         *
         * @param id        物料ID
         * @param code      物料编码
         * @param name      物料名
         * @param missingUom 不存在的单位ID
         */
        public void addProductWithMissingUom(String id, String code, String name, String missingUom)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setUomId(missingUom);
            record.setEnableFlag(ErpMasterGuards.ENABLE_YES);
            products.put(id, record);
        }

        @Override
        public ErpMasterGuards.MasterRecord product(String productId)
        {
            return products.get(productId);
        }

        @Override
        public ErpMasterGuards.MasterRecord warehouse(String warehouseId)
        {
            return warehouses.get(warehouseId);
        }

        @Override
        public ErpMasterGuards.MasterRecord uom(String uomId)
        {
            return uoms.get(uomId);
        }
    }

    /**
     * 入库单表头桩。
     */
    public static class StubStockInMapper implements ErpStockInMapper
    {
        /** id → 单据。 */
        public final Map<String, ErpStockIn> docs = new LinkedHashMap<>();

        /**
         * 直接预置一张单据（夹具）。
         *
         * @param doc 单据
         */
        public void seed(ErpStockIn doc)
        {
            docs.put(doc.getId(), doc);
        }

        @Override
        public List<ErpStockIn> selectStockInList(ErpStockIn query)
        {
            List<ErpStockIn> list = new ArrayList<>();
            for (ErpStockIn doc : docs.values())
            {
                boolean voided = "voided".equals(doc.getStatus());
                if (voided && (query == null || !Boolean.TRUE.equals(query.getIncludeVoided())))
                {
                    continue;
                }
                list.add(doc);
            }
            return list;
        }

        @Override
        public ErpStockIn selectStockInById(String id)
        {
            return docs.get(id);
        }

        @Override
        public int insertStockIn(ErpStockIn doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int updateStockIn(ErpStockIn doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int deleteStockInByIds(String[] ids)
        {
            int count = 0;
            for (String id : ids)
            {
                if (docs.remove(id) != null)
                {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 入库单行项桩。
     */
    public static class StubStockInItemMapper implements ErpStockInItemMapper
    {
        /** docId → 行项。 */
        public final Map<String, List<ErpStockInItem>> items = new LinkedHashMap<>();

        @Override
        public List<ErpStockInItem> selectItemsByDocId(String docId)
        {
            List<ErpStockInItem> list = items.get(docId);
            return list == null ? new ArrayList<ErpStockInItem>() : list;
        }

        @Override
        public int batchInsertItems(List<ErpStockInItem> rows)
        {
            if (rows == null || rows.isEmpty())
            {
                return 0;
            }
            String docId = rows.get(0).getDocId();
            items.put(docId, new ArrayList<>(rows));
            return rows.size();
        }

        @Override
        public int deleteItemsByDocId(String docId)
        {
            List<ErpStockInItem> removed = items.remove(docId);
            return removed == null ? 0 : removed.size();
        }
    }

    /**
     * 出库单表头桩。
     */
    public static class StubStockOutMapper implements ErpStockOutMapper
    {
        /** id → 单据。 */
        public final Map<String, ErpStockOut> docs = new LinkedHashMap<>();

        /**
         * 预置单据（夹具）。
         *
         * @param doc 单据
         */
        public void seed(ErpStockOut doc)
        {
            docs.put(doc.getId(), doc);
        }

        @Override
        public List<ErpStockOut> selectStockOutList(ErpStockOut query)
        {
            List<ErpStockOut> list = new ArrayList<>();
            for (ErpStockOut doc : docs.values())
            {
                boolean voided = "voided".equals(doc.getStatus());
                if (voided && (query == null || !Boolean.TRUE.equals(query.getIncludeVoided())))
                {
                    continue;
                }
                list.add(doc);
            }
            return list;
        }

        @Override
        public ErpStockOut selectStockOutById(String id)
        {
            return docs.get(id);
        }

        /**
         * 按来源单查下游出库单（t29）；与 XML 的
         * {@code source_doc_id = #{sourceDocId} and del_flag = '0' order by create_time, id} 同语义。
         *
         * <p> 桩里同样<b>不过滤状态</b>（与 XML 口径一致），并按 {@code (create_time, id)} 排序，
         * 使"稳定排序"这条口径在单测里也可被观察到。 </p>
         *
         * @param sourceDocId 来源单据ID
         * @return 命中集合（无命中返回空集合）
         */
        @Override
        public List<ErpStockOut> selectBySourceDocId(String sourceDocId)
        {
            List<ErpStockOut> list = new ArrayList<>();
            for (ErpStockOut doc : docs.values())
            {
                if (sourceDocId != null && sourceDocId.equals(doc.getSourceDocId())
                        && "0".equals(doc.getDelFlag()))
                {
                    list.add(doc);
                }
            }
            Collections.sort(list, new java.util.Comparator<ErpStockOut>()
            {
                @Override
                public int compare(ErpStockOut left, ErpStockOut right)
                {
                    long leftTime = left.getCreateTime() == null ? 0L : left.getCreateTime().getTime();
                    long rightTime = right.getCreateTime() == null ? 0L : right.getCreateTime().getTime();
                    if (leftTime != rightTime)
                    {
                        return leftTime < rightTime ? -1 : 1;
                    }
                    String leftId = left.getId() == null ? "" : left.getId();
                    String rightId = right.getId() == null ? "" : right.getId();
                    return leftId.compareTo(rightId);
                }
            });
            return list;
        }

        @Override
        public int insertStockOut(ErpStockOut doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int updateStockOut(ErpStockOut doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int deleteStockOutByIds(String[] ids)
        {
            int count = 0;
            for (String id : ids)
            {
                if (docs.remove(id) != null)
                {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 出库单行项桩。
     */
    public static class StubStockOutItemMapper implements ErpStockOutItemMapper
    {
        /** docId → 行项。 */
        public final Map<String, List<ErpStockOutItem>> items = new LinkedHashMap<>();

        @Override
        public List<ErpStockOutItem> selectItemsByDocId(String docId)
        {
            List<ErpStockOutItem> list = items.get(docId);
            return list == null ? new ArrayList<ErpStockOutItem>() : list;
        }

        @Override
        public int batchInsertItems(List<ErpStockOutItem> rows)
        {
            if (rows == null || rows.isEmpty())
            {
                return 0;
            }
            String docId = rows.get(0).getDocId();
            items.put(docId, new ArrayList<>(rows));
            return rows.size();
        }

        @Override
        public int deleteItemsByDocId(String docId)
        {
            List<ErpStockOutItem> removed = items.remove(docId);
            return removed == null ? 0 : removed.size();
        }
    }
}
