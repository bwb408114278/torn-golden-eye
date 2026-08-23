package pn.torn.goldeneye.torn.service.stocks.readiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据就绪报告写出器单元测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@DisplayName("数据就绪报告写出器测试")
class StockDataReadinessReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写出JSON和Markdown文件且文件名带runId")
    void write_createsJsonAndMarkdown() throws Exception {
        StockDataReadinessReportWriter writer = new StockDataReadinessReportWriter(new ObjectMapper());
        StockDataReadinessReport report = new StockDataReadinessReport(
                "run-123", LocalDateTime.of(2026, 8, 23, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 8, 23, 0, 0),
                "1.0.0", "1.0.0", 35, 1000, 900, 800, 750, 100, 5, 30, "hash");

        Path json = writer.write(tempDir, report);

        assertTrue(Files.exists(json), "应生成summary.json");
        assertTrue(Files.exists(tempDir.resolve("run-123-summary.md")), "应生成summary.md");
        assertTrue(Files.readString(json).contains("run-123"), "JSON应包含runId");
    }
}
