package pn.torn.goldeneye.torn.service.stocks.readiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.MonthlyStateCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteCoverage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据就绪报告写出器单元测试。
 *
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.23
 */
@DisplayName("数据就绪报告写出器测试")
class StockDataReadinessReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写出JSON和Markdown且JSON/Markdown使用同一runId、范围、版本和manifest")
    void write_createsJsonAndMarkdownWithSameFacts() throws Exception {
        StockDataReadinessReportWriter writer = new StockDataReadinessReportWriter(new ObjectMapper());
        StockDataReadinessReport report = new StockDataReadinessReport(
                "run-123", LocalDateTime.of(2026, 8, 23, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 8, 23, 0, 0),
                "1.0.0", "1.0.0", "hash", snapshot());

        Path json = writer.write(tempDir, report);

        assertTrue(Files.exists(json), "应生成summary.json");
        Path md = tempDir.resolve("run-123-summary.md");
        assertTrue(Files.exists(md), "应生成summary.md");
        String jsonText = Files.readString(json);
        String mdText = Files.readString(md);
        assertTrue(jsonText.contains("run-123"), "JSON应包含runId");
        assertTrue(mdText.contains("run-123"), "Markdown应包含runId");
        assertTrue(jsonText.contains("2026-01-01T00:00"), "JSON应包含范围起点");
        assertTrue(mdText.contains("[2026-01-01T00:00, 2026-08-23T00:00)"), "Markdown应包含范围");
        assertTrue(jsonText.contains("\"manifestHash\" : \"hash\""), "JSON应包含manifest");
        assertTrue(mdText.contains("hash"), "Markdown应包含manifest");
        assertTrue(jsonText.contains("\"featureCount\" : 750"), "JSON应包含真实统计而非硬编码零");
    }

    private StockDataReadinessSnapshot snapshot() {
        Map<String, Long> source = new LinkedHashMap<>();
        source.put("TORN_API", 600L);
        source.put("TORNSY_BACKFILL", 400L);
        Map<String, Long> unusable = new LinkedHashMap<>();
        unusable.put("SAMPLE_TOO_FEW", 5L);
        Map<String, Long> notReady = new LinkedHashMap<>();
        notReady.put("INSUFFICIENT_HISTORY", 10L);
        Map<String, Long> rounds = new LinkedHashMap<>();
        rounds.put("REPAIRED_DATA_ONLY", 100L);
        rounds.put("COMPLETED", 50L);
        Map<String, Long> monthIncomplete = new LinkedHashMap<>();
        monthIncomplete.put("MONTHLY_EVIDENCE_INCOMPLETE", 2L);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("VIP_STOCK_ALERT_ENABLED", "true");
        settings.put("VIP_STOCK_RULE_MODE", "SHADOW");

        return new StockDataReadinessSnapshot(
                35,
                List.of(new StockMinuteCoverage(1, "TST",
                        LocalDateTime.of(2026, 1, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 22, 23, 59), 1000L,
                        0L, 0L, 0L, 0L, 0L, 0L, 0L)),
                0L,
                source,
                1000L, 0L, 0L, 0L, 0L, 0L, 0L,
                100_800L, 900L, 800L, unusable, 100_000L,
                750L, 0L, 0L, 700L, notReady,
                List.of(new MonthlyStateCount(LocalDate.of(2026, 1, 1), "CONFIRMED", false, 30L)),
                List.of(),
                monthIncomplete, rounds, 1L, settings);
    }
}
