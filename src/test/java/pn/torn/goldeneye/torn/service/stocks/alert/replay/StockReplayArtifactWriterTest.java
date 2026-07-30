package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pn.torn.goldeneye.torn.service.stocks.alert.StockReplayBoundary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放研究产物原子写入测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放研究产物写入测试")
class StockReplayArtifactWriterTest {

    @TempDir
    Path outputDirectory;

    @Test
    @DisplayName("完成回放_生成summary交易拒绝和净值四类产物")
    void write_completedRun_generatesFourArtifacts() throws Exception {
        StockReplayBoundary boundary = StockReplayBoundary.create("VIP_FORMAL");
        StockReplaySummary summary = StockReplaySummary.completed(boundary, 0);

        new StockReplayArtifactWriter().write(summary, List.of(), List.of(), List.of(), outputDirectory);

        assertTrue(Files.exists(outputDirectory.resolve(boundary.runId() + "-summary.json")));
        assertTrue(Files.exists(outputDirectory.resolve(boundary.runId() + "-trades.csv")));
        assertTrue(Files.exists(outputDirectory.resolve(boundary.runId() + "-rejections.csv")));
        assertTrue(Files.exists(outputDirectory.resolve(boundary.runId() + "-equity-curve.csv")));
        assertTrue(Files.readString(outputDirectory.resolve(boundary.runId() + "-trades.csv"))
                .startsWith("runId,track"));
    }

    @Test
    @DisplayName("已有完成产物_拒绝覆盖")
    void write_existingCompletedArtifact_rejectsOverwrite() throws Exception {
        StockReplayBoundary boundary = StockReplayBoundary.create("VIP_FORMAL");
        StockReplaySummary summary = StockReplaySummary.completed(boundary, 0);
        StockReplayArtifactWriter writer = new StockReplayArtifactWriter();
        writer.write(summary, List.of(), List.of(), List.of(), outputDirectory);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> writer.write(summary, List.of(), List.of(), List.of(), outputDirectory));
    }
}
