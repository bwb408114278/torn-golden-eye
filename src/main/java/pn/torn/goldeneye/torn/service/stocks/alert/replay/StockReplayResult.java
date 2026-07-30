package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.util.List;

/**
 * 回放服务执行结果。
 *
 * @param runId         运行标识
 * @param portfolioId   组合标识
 * @param status        执行状态
 * @param artifactNames 研究产物文件名
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayResult(
        String runId,
        String portfolioId,
        String status,
        List<String> artifactNames
) {
    public StockReplayResult {
        artifactNames = List.copyOf(artifactNames == null ? List.of() : artifactNames);
    }
}
