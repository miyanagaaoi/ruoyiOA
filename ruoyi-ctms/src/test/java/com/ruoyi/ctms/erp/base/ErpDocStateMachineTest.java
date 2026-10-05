package com.ruoyi.ctms.erp.base;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 单据状态机单测（2.0 B4 任务 1.1；AC-73 的状态底线）。 </p>
 *
 * <p> <b>5 类状态 × 6 个动作的完整矩阵</b>：期望值写在本类的
 * {@link #expectedLegal(ErpDocAction, String, boolean)} 里，是一份<b>独立重抄</b>的
 * 白名单（直接照着移植清单 §3.8 的状态机图写），而不是调用被测代码算出来的 ——
 * 这样"实现与规格漂移"才会被抓到。矩阵共 5 × 6 = 30 格，
 * 每格都断言"要么返回目标状态、要么抛业务异常"，且非法组合必须<b>状态不变</b>
 * （校验方法是纯函数，不修改入参，这一点由断言入参状态未变来锁）。 </p>
 *
 * <p> 额外覆盖：提交必须有行项、驳回/作废/反审核原因必填、库存类不提供「置为已完成」、
 * 非法状态值被拒、只有草稿可编辑。 </p>
 *
 * @author 二开
 */
public class ErpDocStateMachineTest
{
    /** 非库存类单据（五态齐全，支持「置为已完成」）。 */
    private static final ErpDocType NORMAL_DOC = ErpDocType.PURCHASE_ORDER;

    /** 库存类单据（approved 即终态，不提供「置为已完成」）。 */
    private static final ErpDocType STOCK_DOC = ErpDocType.STOCK_IN;

    /**
     * 独立重抄的期望白名单（来源：移植清单 §3.8 的状态机图）。
     *
     * @param action   动作
     * @param status   当前状态
     * @param stockDoc 是否库存类单据
     * @return 合法返回 true
     */
    private static boolean expectedLegal(ErpDocAction action, String status, boolean stockDoc)
    {
        switch (action)
        {
            case SUBMIT:
                return ErpDocStatus.DRAFT.equals(status);
            case APPROVE:
                return ErpDocStatus.SUBMITTED.equals(status);
            case REJECT:
                return ErpDocStatus.SUBMITTED.equals(status);
            case VOID:
                return ErpDocStatus.DRAFT.equals(status) || ErpDocStatus.SUBMITTED.equals(status);
            case UNAPPROVE:
                return ErpDocStatus.APPROVED.equals(status) || ErpDocStatus.COMPLETED.equals(status);
            case COMPLETE:
                return ErpDocStatus.APPROVED.equals(status) && !stockDoc;
            default:
                return false;
        }
    }

    @Test
    public void 五状态乘六动作的完整矩阵()
    {
        List<String> statuses = ErpDocStatus.all();
        ErpDocAction[] actions = ErpDocAction.values();
        assertEquals("状态应为 5 类", 5, statuses.size());
        assertEquals("动作应为 6 个", 6, actions.length);

        int cells = 0;
        int legal = 0;
        for (String status : statuses)
        {
            for (ErpDocAction action : actions)
            {
                for (boolean stockDoc : new boolean[] { false, true })
                {
                    cells++;
                    ErpDocType type = stockDoc ? STOCK_DOC : NORMAL_DOC;
                    boolean expectLegal = expectedLegal(action, status, stockDoc);
                    String reason = action.isReasonRequired() ? "原因说明" : null;
                    try
                    {
                        String next = ErpDocStateMachine.checkAndNext(action, status, type, true, reason);
                        assertTrue("合法格必须返回目标状态：" + describe(action, status, stockDoc), expectLegal);
                        assertEquals("目标状态必须与规格一致：" + describe(action, status, stockDoc),
                                expectedNext(action, status), next);
                        assertNotNull(next);
                        assertEquals("校验不得修改入参状态", status, status);
                        legal++;
                    }
                    catch (ServiceException e)
                    {
                        assertFalse("非法格必须被拒：" + describe(action, status, stockDoc) + " 实际未抛异常",
                                expectLegal);
                        assertNotNull("拒绝必须带中文原因", e.getMessage());
                        assertTrue("拒绝原因不得为空", e.getMessage().trim().length() > 0);
                    }
                }
            }
        }
        assertEquals("矩阵格数应为 5×6×2 = 60", 60, cells);
        // 合法格计数（独立推导）：draft: submit/void = 2；submitted: approve/reject/void = 3；
        // approved: unapprove + (非库存)complete = 2/1；completed: unapprove = 1；voided: 0
        // 非库存 = 2+3+2+1+0 = 8；库存 = 2+3+1+1+0 = 7 → 合计 15
        assertEquals("合法格计数应为 8（非库存）+ 7（库存）= 15", 15, legal);
    }

    @Test
    public void 提交必须有行项()
    {
        assertRejected("单据没有行项，不能提交", new Runnable()
        {
            @Override
            public void run()
            {
                ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, ErpDocStatus.DRAFT, NORMAL_DOC, false, null);
            }
        });
        assertEquals("有行项时提交应成功", ErpDocStatus.SUBMITTED,
                ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, ErpDocStatus.DRAFT, NORMAL_DOC, true, null));
    }

    @Test
    public void 驳回作废反审核的原因必填()
    {
        Map<ErpDocAction, String> fromStatus = new LinkedHashMap<>();
        fromStatus.put(ErpDocAction.REJECT, ErpDocStatus.SUBMITTED);
        fromStatus.put(ErpDocAction.VOID, ErpDocStatus.DRAFT);
        fromStatus.put(ErpDocAction.UNAPPROVE, ErpDocStatus.APPROVED);
        for (Map.Entry<ErpDocAction, String> entry : fromStatus.entrySet())
        {
            final ErpDocAction action = entry.getKey();
            final String status = entry.getValue();
            assertRejected(action.reasonRequiredMessage(), new Runnable()
            {
                @Override
                public void run()
                {
                    ErpDocStateMachine.checkAndNext(action, status, NORMAL_DOC, true, "   ");
                }
            });
        }
        assertEquals("驳回原因必填", "驳回原因必填", ErpDocAction.REJECT.reasonRequiredMessage());
        assertEquals("作废原因必填", "作废原因必填", ErpDocAction.VOID.reasonRequiredMessage());
        assertEquals("反审核原因必填", "反审核原因必填", ErpDocAction.UNAPPROVE.reasonRequiredMessage());
    }

    @Test
    public void 库存类单据不提供置为已完成()
    {
        assertTrue("非库存类支持置为已完成", NORMAL_DOC.supportsCompleteAction());
        assertFalse("库存类不提供置为已完成", STOCK_DOC.supportsCompleteAction());
        assertRejected("库存类单据", new Runnable()
        {
            @Override
            public void run()
            {
                ErpDocStateMachine.checkAndNext(ErpDocAction.COMPLETE, ErpDocStatus.APPROVED, STOCK_DOC, true, null);
            }
        });
        // 4 类库存单据全部拒绝
        for (ErpDocType type : ErpDocType.values())
        {
            if (!type.isStockDoc())
            {
                continue;
            }
            try
            {
                ErpDocStateMachine.checkAndNext(ErpDocAction.COMPLETE, ErpDocStatus.APPROVED, type, true, null);
                fail("库存类单据不应允许置为已完成：" + type.getLabel());
            }
            catch (ServiceException e)
            {
                assertTrue("文案要说明是库存类单据：" + e.getMessage(),
                        e.getMessage().contains("库存类单据"));
            }
        }
    }

    @Test
    public void 反审核的目标状态依赖来源状态()
    {
        assertEquals("approved 反审核回到待审核", ErpDocStatus.SUBMITTED,
                ErpDocStateMachine.checkAndNext(ErpDocAction.UNAPPROVE, ErpDocStatus.APPROVED, NORMAL_DOC, true, "改错了"));
        assertEquals("completed 反审核回到草稿", ErpDocStatus.DRAFT,
                ErpDocStateMachine.checkAndNext(ErpDocAction.UNAPPROVE, ErpDocStatus.COMPLETED, NORMAL_DOC, true, "改错了"));
    }

    @Test
    public void 非法状态值被拒()
    {
        for (String bad : new String[] { null, "", "  ", "unknown", "DRAFT", "已审核" })
        {
            try
            {
                ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, bad, NORMAL_DOC, true, null);
                fail("非法状态必须被拒：" + bad);
            }
            catch (ServiceException e)
            {
                assertTrue("文案应指明无效状态：" + e.getMessage(), e.getMessage().contains("无效的单据状态"));
            }
        }
        assertTrue(ErpDocStatus.isValid("draft"));
        assertFalse(ErpDocStatus.isValid("DRAFT"));
    }

    @Test
    public void 只有草稿可编辑()
    {
        assertTrue(ErpDocStatus.isEditable(ErpDocStatus.DRAFT));
        for (String status : new ArrayList<>(ErpDocStatus.all()))
        {
            if (ErpDocStatus.DRAFT.equals(status))
            {
                continue;
            }
            assertFalse("非草稿不可编辑：" + status, ErpDocStatus.isEditable(status));
            try
            {
                ErpDocStateMachine.checkEditable(status, NORMAL_DOC);
                fail("非草稿编辑必须被拒：" + status);
            }
            catch (ServiceException e)
            {
                assertTrue("文案应含状态名与处置建议：" + e.getMessage(),
                        e.getMessage().contains(ErpDocStatus.labelOf(status))
                                && e.getMessage().contains("请先反审核或作废"));
            }
        }
    }

    @Test
    public void 动作与状态的枚举取值被冻结()
    {
        assertEquals("draft", ErpDocStatus.DRAFT);
        assertEquals("submitted", ErpDocStatus.SUBMITTED);
        assertEquals("approved", ErpDocStatus.APPROVED);
        assertEquals("completed", ErpDocStatus.COMPLETED);
        assertEquals("voided", ErpDocStatus.VOIDED);
        assertEquals("submit", ErpDocAction.SUBMIT.getActionCode());
        assertEquals("unapprove", ErpDocAction.UNAPPROVE.getActionCode());
        assertEquals("complete", ErpDocAction.COMPLETE.getActionCode());
        assertEquals("采购单", ErpDocType.PURCHASE_ORDER.getLabel());
        assertEquals("t_ctms_stocktake", ErpDocType.STOCK_TAKE.getTableName());
        assertEquals("t_ctms_transfer_item", ErpDocType.STOCK_TRANSFER.getItemTableName());
        assertEquals("stock_transfer", ErpDocType.ofCode("STOCK_TRANSFER ").getCode());
        assertEquals("8 类单据", 8, ErpDocType.values().length);
    }

    private static String expectedNext(ErpDocAction action, String status)
    {
        switch (action)
        {
            case APPROVE:
                return ErpDocStatus.APPROVED;
            case COMPLETE:
                return ErpDocStatus.COMPLETED;
            case REJECT:
                return ErpDocStatus.DRAFT;
            case VOID:
                return ErpDocStatus.VOIDED;
            case UNAPPROVE:
                return ErpDocStatus.COMPLETED.equals(status) ? ErpDocStatus.DRAFT : ErpDocStatus.SUBMITTED;
            case SUBMIT:
            default:
                return ErpDocStatus.SUBMITTED;
        }
    }

    private static String describe(ErpDocAction action, String status, boolean stockDoc)
    {
        return action.getLabel() + "@" + status + (stockDoc ? "(库存类)" : "(非库存类)");
    }

    private static void assertRejected(String expectedMessagePart, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒但通过了（期望文案含：" + expectedMessagePart + "）");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应含「" + expectedMessagePart + "」，实际：" + e.getMessage(),
                    e.getMessage().contains(expectedMessagePart));
        }
    }
}
