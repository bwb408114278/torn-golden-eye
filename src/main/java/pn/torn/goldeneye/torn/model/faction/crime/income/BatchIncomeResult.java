package pn.torn.goldeneye.torn.model.faction.crime.income;

import java.util.List;

/**
 * 批量收益计算统计结果。
 *
 * <p>由批量门面在批次结束后返回，供调用方与测试校验校准闭环。等待父节点可等待后续
 * 分页或刷新，不算失败；只有等待终点、failure、abnormal、abnormalIncomplete均为0时才可进入排行榜验收。</p>
 *
 * @param candidateCount               参与本批次的叶子候选数量
 * @param successCount                 单链事务成功数量
 * @param failureCount                 单链事务失败数量
 * @param waitingChainParentCount      因等待真实后继节点而暂不计算的配置链父节点数量
 * @param alreadyCalculatedCount       整链或叶子已结算而跳过数量
 * @param abnormalPartialIncomeCount   链内存在部分income的异常数量
 * @param abnormalIncompleteChainCount 链回溯祖先缺失/环/帮派不一致的异常数量
 * @param skippedCount                 叶子不再适用而跳过数量
 * @param abnormalChains               异常部分income链的详细信息
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
public record BatchIncomeResult(
        int candidateCount,
        int successCount,
        int failureCount,
        int waitingChainParentCount,
        int alreadyCalculatedCount,
        int abnormalPartialIncomeCount,
        int abnormalIncompleteChainCount,
        int skippedCount,
        List<SingleChainResult> abnormalChains) {

    /**
     * 构造无任何候选与统计的空结果。
     *
     * @return 空批次结果
     */
    public static BatchIncomeResult empty() {
        return new BatchIncomeResult(0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    /**
     * 构造仅含等待后继父节点统计的结果（无可处理候选）。
     *
     * @param candidateCount 参与本批次的叶子候选数量
     * @param waitingCount   等待链式后继节点的父节点数量
     * @return 仅等待统计的批次结果
     */
    public static BatchIncomeResult waitingOnly(int candidateCount, int waitingCount) {
        return new BatchIncomeResult(candidateCount, 0, 0, waitingCount, 0, 0, 0, 0, List.of());
    }
}
