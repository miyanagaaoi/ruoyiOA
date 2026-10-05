package com.ruoyi.ctms.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.ruoyi.ctms.domain.CtmsContract;

/**
 * <p> 质保到期提醒的返回对象（2.0 B3 任务 5.6，规格 ctms/contract-commercials 的
 * 「质保到期提醒与释放闭环」Requirement）。 </p>
 *
 * <p> 两个列表的判定口径（都<b>只</b>覆盖「启用质保 + 未释放 + 到期日非空 + 未停用」的合同）： </p>
 * <ul>
 *   <li> {@code expiring}（即将到期）：到期日 ∈ [今天, 今天 + {@code windowDays}]； </li>
 *   <li> {@code expired}（已到期）：到期日 &lt; 今天。 </li>
 * </ul>
 *
 * <p> 两个列表互斥、并按到期日升序、各最多 100 条（规格"看板区最多 100 条、按到期日升序"）。
 * 合同被标记质保已释放后<b>同时</b>从两个列表消失。 </p>
 *
 * <p> 顺带把算窗口用的 {@code windowDays} 与"今天"一起返回：验收与前端都不必再猜服务端用的是哪一天
 * （窗口天数来自 {@code sys_config.warranty_window_days}，改参数后行为随之变化）。 </p>
 *
 * @author 二开
 */
public class CtmsWarrantyReminderVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 即将到期（到期日 ∈ [今天, 今天 + 窗口天数]） */
    private List<CtmsContract> expiring = new ArrayList<>();

    /** 已到期（到期日 < 今天） */
    private List<CtmsContract> expired = new ArrayList<>();

    /** 本次判定用的提醒窗口天数（来自 {@code sys_config.warranty_window_days}） */
    private int windowDays;

    /** 本次判定用的"今天"（{@code yyyy-MM-dd}，服务端时钟；便于验收比对与排查） */
    private String today;

    public List<CtmsContract> getExpiring()
    {
        return expiring;
    }

    public void setExpiring(List<CtmsContract> expiring)
    {
        this.expiring = expiring;
    }

    public List<CtmsContract> getExpired()
    {
        return expired;
    }

    public void setExpired(List<CtmsContract> expired)
    {
        this.expired = expired;
    }

    public int getWindowDays()
    {
        return windowDays;
    }

    public void setWindowDays(int windowDays)
    {
        this.windowDays = windowDays;
    }

    public String getToday()
    {
        return today;
    }

    public void setToday(String today)
    {
        this.today = today;
    }
}
