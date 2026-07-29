package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCandidateAllocationResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.util.List;
import java.util.Map;

/**
 * 股票候选正式接纳结果，明确区分资格通过、实际槽位分配和失败原因。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record StockCandidateAllocationResult(
        List<TornStockVirtualBatchDO> formalBatches,
        Map<Integer, StockCandidateAllocationResultEnum> resultByStockId
) {
    /**
     * 创建空接纳结果。
     *
     * @return 空结果
     */
    public static StockCandidateAllocationResult empty() {
        return new StockCandidateAllocationResult(List.of(), Map.of());
    }
}
