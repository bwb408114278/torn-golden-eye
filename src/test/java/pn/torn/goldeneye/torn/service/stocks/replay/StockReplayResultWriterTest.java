package pn.torn.goldeneye.torn.service.stocks.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayResult;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySourceManifest;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySummary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回放产物写入器与输入来源清单领域测试。
 * <p>
 * 保护P1-2机制: 仅COMPLETED四产物成为不可覆盖完成标识;相同{@code runId + sourceManifestHash}
 * 拒绝覆盖;不同输入代际不误判为同一次成功;FAILED诊断写入独立attempt目录且不阻塞同runId重跑;
 * 清单hash由相同输入可复算。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@DisplayName("回放产物写入与来源清单测试")
class StockReplayResultWriterTest {

    @TempDir
    Path tempDir;

    private final StockReplayResultWriter writer = new StockReplayResultWriter();

    private static final String RUN_ID = "replay-unit";

    @Test
    @DisplayName("writeCompleted_成功写入四类产物")
    void writeCompleted_producesFourArtifacts() {
        StockReplayResult result = result("COMPLETED", manifest(0));
        String dir = writer.writeCompleted(RUN_ID, tempDir.toString(), result);

        for (String suffix : List.of("-summary.json", "-trades.csv", "-rejections.csv", "-equity-curve.csv")) {
            assertTrue(Files.exists(Paths.get(dir, RUN_ID + suffix)), "缺少产物: " + RUN_ID + suffix);
        }
    }

    @Test
    @DisplayName("writeCompleted_相同runId与相同输入代际_拒绝覆盖")
    void writeCompleted_sameGeneration_rejectsOverwrite() {
        writer.writeCompleted(RUN_ID, tempDir.toString(), result("COMPLETED", manifest(0)));

        String outputRoot = tempDir.toString();
        StockReplayResult duplicate = result("COMPLETED", manifest(0));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> writer.writeCompleted(RUN_ID, outputRoot, duplicate),
                "已完成同代际必须拒绝覆盖");
        assertTrue(e.getMessage().contains("相同输入代际完成"), "拒绝信息应说明同代际已完成");
    }

    @Test
    @DisplayName("writeCompleted_相同runId不同输入代际_拒绝覆盖且不误判成功")
    void writeCompleted_differentGeneration_rejectsOverwrite() {
        writer.writeCompleted(RUN_ID, tempDir.toString(), result("COMPLETED", manifest(0)));

        String outputRoot = tempDir.toString();
        StockReplayResult differentGeneration = result("COMPLETED", manifest(1));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> writer.writeCompleted(RUN_ID, outputRoot, differentGeneration),
                "不同输入代际不得误判为同一次成功");
        assertTrue(e.getMessage().contains("不同输入代际"), "拒绝信息应说明代际冲突");
    }

    @Test
    @DisplayName("writeFailed_不占用完成标识_同runId可从头重跑")
    void writeFailed_doesNotBlockRetry() {
        writer.writeFailed(RUN_ID, "attempt-1", tempDir.toString(), result("FAILED", null));

        String dir = writer.writeCompleted(RUN_ID, tempDir.toString(), result("COMPLETED", manifest(0)));

        assertTrue(Files.exists(Paths.get(dir, RUN_ID + "-summary.json")), "失败后同runId应能从头成功重跑");
        assertTrue(Files.exists(Paths.get(tempDir.toString(), RUN_ID + "-failed", "attempt-1",
                RUN_ID + "-summary.json")), "失败诊断应保留在独立attempt目录");
    }

    @Test
    @DisplayName("writeFailed_仅写诊断摘要_不产生成功产物目录")
    void writeFailed_writesOnlyDiagnosticSummary() {
        String dir = writer.writeFailed(RUN_ID, "attempt-2", tempDir.toString(), result("FAILED", null));

        assertTrue(Files.exists(Paths.get(dir, RUN_ID + "-summary.json")), "失败摘要应落盘");
        assertFalse(Files.exists(Paths.get(tempDir.toString(), RUN_ID, RUN_ID + "-summary.json")),
                "失败不得占用成功产物目录与完成标识");
    }

    @Test
    @DisplayName("来源清单_相同输入_摘要可复算一致")
    void sourceManifest_sameInputs_sameHash() {
        assertEquals(manifest(0).sha256(), manifest(0).sha256(), "相同输入清单hash必须一致");
    }

    @Test
    @DisplayName("来源清单_不同输入代际_摘要不同")
    void sourceManifest_differentInputs_differentHash() {
        assertNotEquals(manifest(0).sha256(), manifest(1).sha256(), "不同输入代际hash必须不同");
    }

    private static StockReplayResult result(String status, StockReplaySourceManifest manifest) {
        StockReplaySummary summary = new StockReplaySummary(
                RUN_ID, status, StockReplayRunner.ANNUALIZED_BACKTEST_MARKER,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 0, 0), 31,
                "1.0.0", "1.0.0", "1.1.0", "1.0.0",
                manifest, List.of(), null);
        return new StockReplayResult(RUN_ID, summary, List.of(), List.of(), List.of());
    }

    private static StockReplaySourceManifest manifest(long barCount) {
        return StockReplaySourceManifest.of(
                new StockReplaySourceManifest.WindowRange(
                        LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 0, 0),
                        LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 14, 0, 0),
                        LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 14, 0, 0),
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)),
                new StockReplaySourceManifest.Versions("1.0.0", "1.0.0",
                        List.of("PERSONALITY_RULE_V1|RISK_RULE_V1_SHADOW")),
                barCount, 0, 0, List.of());
    }
}
