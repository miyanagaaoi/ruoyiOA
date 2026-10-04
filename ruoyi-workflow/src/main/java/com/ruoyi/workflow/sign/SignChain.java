package com.ruoyi.workflow.sign;

import com.ruoyi.workflow.domain.SignRecord;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p> 签名记录的**顺序**（PRD 8.5 哈希链） </p>
 *
 * <p> <b>为什么不能用 {@code order by sign_time desc, id desc} 取"最新一条"</b>：
 * {@code sign_time} 只精确到秒，而 {@code id} 是随机 UUID —— 同一秒内发生的
 * "重签 + 撤销"两条记录，谁排在前面完全看 uuid 大小。实测就踩到了：先重签、后撤销，
 * 但撤销的 uuid 更小，于是"每个节点最新一条"判成了重签那条，**撤签后该节点仍然算已签**
 * （后果不是显示问题：AC-26 的"必需签名"校验也因此被绕过）。 </p>
 *
 * <p> 真正的顺序只有一个来源：<b>哈希链本身</b>。
 * {@code prev_hash} 指向它的前一条，所以从"头"（没有任何记录指向它）沿链走到底就是时间序。
 * 本类就干这一件事，供 {@code SignServiceImpl} 取"最新一条 / 链尾 / 每个任务的最新一条"。 </p>
 *
 * <p> 链断裂（历史数据、并发写入造成分叉）时不抛异常：先按链能走到的部分排序，
 * 剩下的按 {@code sign_time, id} 兜底追加 —— 顺序不完美，但绝不丢记录。 </p>
 *
 * @author 二开
 */
public final class SignChain {

    private SignChain() {
    }

    /**
     * 按哈希链还原顺序（从最早到最晚）。
     *
     * @param all 某单据的全部签名记录（顺序无所谓）
     * @return 新列表，链序；入参为 null 时返回空列表
     */
    public static List<SignRecord> order(List<SignRecord> all) {
        List<SignRecord> list = new ArrayList<>();
        if (all != null) {
            for (SignRecord r : all) {
                if (r != null) {
                    list.add(r);
                }
            }
        }
        // 兜底排序：链走不到的部分、以及"谁是头"的取舍都靠它保持稳定
        list.sort(Comparator
                .comparing(SignRecord::getSignTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(SignRecord::getId, Comparator.nullsFirst(Comparator.naturalOrder())));

        Map<String, SignRecord> byHash = new HashMap<>();
        for (SignRecord r : list) {
            if (StringUtils.isNotBlank(r.getRecordHash())) {
                byHash.put(r.getRecordHash(), r);
            }
        }
        // prev_hash -> 它的后继们
        Map<String, List<SignRecord>> children = new LinkedHashMap<>();
        Set<String> hasPredecessor = new HashSet<>();
        for (SignRecord r : list) {
            String prev = r.getPrevHash();
            if (StringUtils.isNotBlank(prev) && byHash.containsKey(prev)) {
                children.computeIfAbsent(prev, k -> new ArrayList<>()).add(r);
                hasPredecessor.add(r.getId());
            }
        }

        List<SignRecord> out = new ArrayList<>(list.size());
        Set<String> visited = new HashSet<>();
        // 头：没有任何记录以它为 prev（prev 为空，或 prev 指向的哈希不在本单据里）
        for (SignRecord r : list) {
            if (!hasPredecessor.contains(r.getId())) {
                walk(r, children, visited, out);
            }
        }
        // 分叉/成环的兜底：剩下的按 sign_time 顺序补上，一条都不丢
        for (SignRecord r : list) {
            walk(r, children, visited, out);
        }
        return out;
    }

    private static void walk(SignRecord node,
                             Map<String, List<SignRecord>> children,
                             Set<String> visited,
                             List<SignRecord> out) {
        if (node == null || !visited.add(node.getId())) {
            return;
        }
        out.add(node);
        List<SignRecord> next = children.get(node.getRecordHash());
        if (next != null) {
            for (SignRecord child : next) {
                walk(child, children, visited, out);
            }
        }
    }

    /**
     * 链尾（最后一条）—— 取 {@code prev_hash} 用。
     *
     * @return 没有记录时返回 null
     */
    public static SignRecord tip(List<SignRecord> all) {
        List<SignRecord> ordered = order(all);
        return ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
    }

    /**
     * 每个任务当前最新的一条（链序里最后一次出现的）。
     *
     * <p> 返回的可能是<b>撤销</b>记录（{@code signType=9}）—— 调用方据此判断"该节点已作废"，
     * 不要在这里过滤掉，否则"签了又撤"就看不出来了。 </p>
     *
     * @param all 某单据的全部签名记录
     * @return taskId -&gt; 最新一条；没有 taskId 的记录被忽略
     */
    public static Map<String, SignRecord> latestByTask(List<SignRecord> all) {
        Map<String, SignRecord> out = new HashMap<>();
        for (SignRecord r : order(all)) {
            if (StringUtils.isNotBlank(r.getTaskId())) {
                out.put(r.getTaskId(), r);
            }
        }
        return out;
    }
}
