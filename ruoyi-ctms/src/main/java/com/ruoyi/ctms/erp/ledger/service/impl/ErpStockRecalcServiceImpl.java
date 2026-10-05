package com.ruoyi.ctms.erp.ledger.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.ctms.erp.ledger.domain.ErpLedgerQtySum;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockRecalcResult;
import com.ruoyi.ctms.erp.ledger.mapper.ErpStockLedgerQueryMapper;
import com.ruoyi.ctms.erp.ledger.service.IErpStockRecalcService;
import com.ruoyi.ctms.erp.posting.domain.ErpStock;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockMapper;

/**
 * <p> <b>库存结存一致性校验 / 修复实现</b>（B4 任务 7.4；AC-76）。 </p>
 *
 * <p> <b>口径："流水是账、结存是缓存"</b>。结存列只是为了查询快，真正的账是流水累计；
 * 因此一致性 = 逐（物料, 仓库）比对 {@code stock.qty} 与 {@code Σ ledger.qty_change}，
 * 修复 = 把结存改写成流水累计。 </p>
 *
 * <p> <b>取数只两条 SQL</b>：结存全量（T3 的 {@code selectStockList}）+ 流水按 key 聚合
 * （{@code selectLedgerQtySums}），避免按行 N+1；修复时才逐行
 * {@code updateStockQty}（写绝对值，复用 T3 的唯一写入口）。 </p>
 *
 * <p> <b>刻意不处理的边界</b>：只检查"结存行"。若某（物料, 仓库）只有流水、没有结存行
 * （正常过账路径不可能出现：两者在同一事务里写入），它既不会被计成不一致、
 * 也不会被修复——凭空补一行结存超出"改写"的授权范围。这条边界写在
 * {@code notes/07-ledger.md} 的"已知边界"里，并由 T13 的接口级检查兜住。 </p>
 *
 * @author 二开
 */
@Service
public class ErpStockRecalcServiceImpl implements IErpStockRecalcService
{
    /** 结存读写 Mapper（T3 的写入侧；本服务只用它的只读方法与 updateStockQty）。 */
    @Autowired
    private ErpStockMapper stockMapper;

    /** 流水只读 Mapper（取按 key 的累计和）。 */
    @Autowired
    private ErpStockLedgerQueryMapper stockLedgerQueryMapper;

    @Override
    public ErpStockRecalcResult recalc(boolean repair)
    {
        List<ErpStock> stocks = stockMapper.selectStockList(new ErpStock());
        if (stocks == null)
        {
            stocks = new ArrayList<>();
        }
        Map<String, BigDecimal> ledgerSums = loadLedgerSums();

        ErpStockRecalcResult result = new ErpStockRecalcResult();
        result.setRepair(repair);
        result.setCheckedRows(stocks.size());

        List<ErpStockRecalcResult.Mismatch> mismatches = new ArrayList<>();
        int repairedCount = 0;
        for (ErpStock stock : stocks)
        {
            if (stock == null)
            {
                continue;
            }
            BigDecimal stockQty = stock.qtyOrZero();
            BigDecimal ledgerQty = ledgerSums.get(key(stock.getProductId(), stock.getWarehouseId()));
            BigDecimal expected = ledgerQty == null ? BigDecimal.ZERO : ledgerQty;
            if (stockQty.compareTo(expected) == 0)
            {
                continue;
            }
            mismatches.add(new ErpStockRecalcResult.Mismatch(stock.getProductId(), stock.getWarehouseId(),
                    stockQty, expected));
            if (repair)
            {
                // 写绝对值：修复后的结存必须逐字节等于"流水累计"，不做增量累加
                stockMapper.updateStockQty(stock.getId(), expected);
                repairedCount++;
            }
        }
        result.setMismatches(mismatches);
        result.setInconsistentCount(mismatches.size());
        result.setConsistent(mismatches.isEmpty());
        result.setRepairedCount(repairedCount);
        return result;
    }

    /**
     * 载入全部 key 的流水累计和。
     *
     * @return key → 累计和
     */
    private Map<String, BigDecimal> loadLedgerSums()
    {
        Map<String, BigDecimal> sums = new HashMap<>();
        List<ErpLedgerQtySum> rows = stockLedgerQueryMapper.selectLedgerQtySums();
        if (rows == null)
        {
            return sums;
        }
        for (ErpLedgerQtySum row : rows)
        {
            if (row == null)
            {
                continue;
            }
            sums.put(key(row.getProductId(), row.getWarehouseId()),
                    row.getQtySum() == null ? BigDecimal.ZERO : row.getQtySum());
        }
        return sums;
    }

    /**
     * 结存/流水的聚合键（{@code productId + \u0001 + warehouseId}）。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 聚合键
     */
    private static String key(String productId, String warehouseId)
    {
        return String.valueOf(productId) + "\u0001" + String.valueOf(warehouseId);
    }
}
