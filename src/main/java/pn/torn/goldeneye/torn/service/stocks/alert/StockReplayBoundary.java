package pn.torn.goldeneye.torn.service.stocks.alert;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 股票隔离回放边界 - 固定回放身份和研究产物名称,不接入正式组合持久化。
 *
 * @param portfolioId 回放组合标识
 * @param runId       回放运行标识
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.29
 */
public record StockReplayBoundary(String portfolioId, String runId) {
    /**
     * 回放研究产物名称。
     */
    public static final List<String> RESEARCH_ARTIFACTS = List.of(
            "summary.json",
            "trades.csv",
            "rejections.csv",
            "equity-curve.csv"
    );

    /**
     * 创建一个新的隔离回放边界。
     *
     * @param portfolioId 回放组合标识
     * @return 新的回放边界
     */
    public static StockReplayBoundary create(String portfolioId) {
        return new StockReplayBoundary(portfolioId, UUID.randomUUID().toString());
    }

    /**
     * 紧缩边界字段并校验不能为空。
     */
    public StockReplayBoundary {
        portfolioId = requireValue(portfolioId, "portfolioId");
        runId = requireValue(runId, "runId");
    }

    private static String requireValue(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName + "不能为空").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return normalized;
    }
}
