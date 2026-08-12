package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCandidateAllocationResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.util.List;
import java.util.Map;

/**
 * 股票候选接纳结果，记录本轮实际接纳的轨道批次与逐股接纳结论。
 * <p>
 * 结果批次在正式模式下作为正式批次写入，在候选影子模式下被复用为候选影子批次。
 *
 * @param allocatedBatches 本轮创建的轨道批次（正式模式为正式批次，候选影子模式复用为候选影子批次）
 * @param resultByStockId  股票ID到接纳结论的映射
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.17
 */
public record StockCandidateAllocationResult(
        List<TornStockVirtualBatchDO> allocatedBatches,
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
