package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.CandidateInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 股票轮次平仓保护工具，避免本轮正式/候选影子平仓股票立即重新开仓。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.17
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockRoundExitGuard {

    /**
     * 排除本轮已完成正式或候选影子平仓的股票候选。
     * <p>
     * 平仓冷却在轮次末尾统一回写，为避免同一事务内候选接纳使用旧状态快照，
     * 本方法仅在本轮直接阻止已完成正式/候选影子平仓的股票创建新的槽位批次。
     * 无限资金影子平仓不影响槽位候选。
     *
     * @param rankedCandidates  已按质量排序的槽位候选
     * @param exitFilledBatches 本轮已实际成交平仓的批次
     * @return 排除本轮正式/候选影子平仓股票后的候选列表
     */
    public static List<CandidateInfo> excludeFormalExitStocks(
            List<CandidateInfo> rankedCandidates,
            List<TornStockVirtualBatchDO> exitFilledBatches) {
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return List.of();
        }
        Set<Integer> formalExitStockIds = collectFormalExitStockIds(exitFilledBatches);
        if (formalExitStockIds.isEmpty()) {
            return rankedCandidates;
        }
        return rankedCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !formalExitStockIds.contains(candidate.stocksId()))
                .toList();
    }

    /**
     * 提取本轮已实际完成正式或候选影子平仓的股票ID。
     *
     * @param exitFilledBatches 本轮已实际成交平仓的批次
     * @return 正式/候选影子平仓股票ID集合
     */
    private static Set<Integer> collectFormalExitStockIds(List<TornStockVirtualBatchDO> exitFilledBatches) {
        Set<Integer> formalExitStockIds = new HashSet<>();
        if (exitFilledBatches == null || exitFilledBatches.isEmpty()) {
            return formalExitStockIds;
        }
        for (TornStockVirtualBatchDO batch : exitFilledBatches) {
            if (batch != null
                    && (StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType())
                    || StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode().equals(batch.getLedgerType()))
                    && batch.getStocksId() != null) {
                formalExitStockIds.add(batch.getStocksId());
            }
        }
        return formalExitStockIds;
    }
}
