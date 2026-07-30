package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pn.torn.goldeneye.torn.service.stocks.alert.StockReplayBoundary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票回放研究产物字段契约测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放研究产物字段契约测试")
class StockReplayArtifactContractTest {

    @TempDir
    Path outputDirectory;

    @Test
    @DisplayName("摘要_包含输入版本时间范围数据状态和错误字段")
    void summary_containsInputVersionsTimeRangeAndDataFields() throws Exception {
        StockReplaySummary summary = new StockReplaySummary(
                "run-1", "VIP_FORMAL", "COMPLETED", "2026-01-01T00:00", "2026-01-02T00:00",
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                "FORMAL_5_SLOT", "100", "100", "0", "0", "0", 1, 2, "COMPLETE", "");

        new StockReplayArtifactWriter().write(summary, List.of(), List.of(), List.of(), outputDirectory);

        JsonNode json = new ObjectMapper().readTree(Files.readString(
                outputDirectory.resolve("run-1-summary.json")));
        assertEquals("BAR_V1", json.get("barBuildVersion").asText());
        assertEquals("FEATURE_V1", json.get("featureVersion").asText());
        assertEquals("COMPLETE", json.get("dataStatus").asText());
        assertTrue(json.has("errors"));
        assertTrue(json.has("slotUtilization"));
    }

    @Test
    @DisplayName("交易和拒绝CSV_包含策略收益和观察字段")
    void csv_containsStrategyReturnsAndObservationFields() throws Exception {
        StockReplayBoundary boundary = StockReplayBoundary.create("VIP_FORMAL");
        StockReplayTrade trade = new StockReplayTrade(boundary.runId(), StockReplayTrackEnum.FORMAL_5_SLOT,
                1001, "STRATEGY", null, null, "100.00", "101.00", 2, "0.01", "0.008990",
                "CLOSED_TARGET");
        StockReplayRejection rejection = new StockReplayRejection(boundary.runId(),
                StockReplayTrackEnum.REJECTED_OBSERVATION, 1001, "STRATEGY", null, "STYLE_MISSING",
                "OBSERVATION_COMPLETED", "0.010", "-0.002");
        new StockReplayArtifactWriter().write(StockReplaySummary.completed(boundary, 1),
                List.of(trade), List.of(rejection), List.of(), outputDirectory);

        String trades = Files.readString(outputDirectory.resolve(boundary.runId() + "-trades.csv"));
        String rejections = Files.readString(outputDirectory.resolve(boundary.runId() + "-rejections.csv"));
        assertTrue(trades.startsWith("runId,track,stocksId,strategy"));
        assertTrue(trades.contains("0.01"));
        assertTrue(rejections.startsWith("runId,track,stocksId,strategy"));
        assertTrue(rejections.contains("OBSERVATION_COMPLETED"));
    }
}
