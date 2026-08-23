package pn.torn.goldeneye.torn.service.stocks.readiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 股票数据就绪报告本地写出器。
 * <p>
 * 在本地只读环境将 {@link StockDataReadinessReport} 输出为同 runId 的
 * {@code <runId>-summary.json} 与 {@code <runId>-summary.md}，使用临时文件 + 原子改名，
 * 不写生产数据库。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDataReadinessReportWriter {

    private final ObjectMapper objectMapper;

    /**
     * 输出指定报告到目录。
     *
     * @param outputDir 输出目录（不存在时自动创建）
     * @param report    就绪报告模型
     * @return 生成的主文件路径（summary.json）
     * @throws IOException 文件写出失败时抛出
     */
    public Path write(Path outputDir, StockDataReadinessReport report) throws IOException {
        Files.createDirectories(outputDir);
        String baseName = report.runId() + "-summary";
        Path jsonTarget = outputDir.resolve(baseName + ".json");
        Path mdTarget = outputDir.resolve(baseName + ".md");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toMap(report));
        String markdown = buildMarkdown(report);

        writeAtomically(jsonTarget, json);
        writeAtomically(mdTarget, markdown);
        log.info("数据就绪报告已输出, runId={}, json={}, markdown={}", report.runId(), jsonTarget, mdTarget);
        return jsonTarget;
    }

    private java.util.Map<String, Object> toMap(StockDataReadinessReport report) {
        return java.util.Map.ofEntries(
                java.util.Map.entry("runId", report.runId()),
                java.util.Map.entry("generatedAt", report.generatedAt() == null ? null : report.generatedAt().toString()),
                java.util.Map.entry("startInclusive", report.startInclusive() == null ? null : report.startInclusive().toString()),
                java.util.Map.entry("endExclusive", report.endExclusive() == null ? null : report.endExclusive().toString()),
                java.util.Map.entry("barBuildVersion", report.barBuildVersion()),
                java.util.Map.entry("featureVersion", report.featureVersion()),
                java.util.Map.entry("stockCount", report.stockCount()),
                java.util.Map.entry("minuteFactCount", report.minuteFactCount()),
                java.util.Map.entry("barCount", report.barCount()),
                java.util.Map.entry("usableBarCount", report.usableBarCount()),
                java.util.Map.entry("featureCount", report.featureCount()),
                java.util.Map.entry("repairedDataOnlyRoundCount", report.repairedDataOnlyRoundCount()),
                java.util.Map.entry("draftMonthCount", report.draftMonthCount()),
                java.util.Map.entry("confirmedMonthCount", report.confirmedMonthCount()),
                java.util.Map.entry("manifestHash", report.manifestHash()));
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String buildMarkdown(StockDataReadinessReport report) {
        return """
                # VIP 股票数据就绪报告

                - runId: %s
                - 生成时刻: %s
                - 范围: [%s, %s)
                - bar版本: %s
                - feature版本: %s
                - 股票数: %d
                - 分钟事实行数: %d
                - bar行数: %d
                - 可用bar行数: %d
                - feature行数: %d
                - REPAIRED_DATA_ONLY轮次数: %d
                - DRAFT月度状态数: %d
                - CONFIRMED月度状态数: %d
                - manifestHash: %s
                """.formatted(
                report.runId(), report.generatedAt(), report.startInclusive(), report.endExclusive(),
                report.barBuildVersion(), report.featureVersion(), report.stockCount(),
                report.minuteFactCount(), report.barCount(), report.usableBarCount(), report.featureCount(),
                report.repairedDataOnlyRoundCount(), report.draftMonthCount(), report.confirmedMonthCount(),
                report.manifestHash());
    }
}
