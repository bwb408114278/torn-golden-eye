package pn.torn.goldeneye.torn.service.stocks.readiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.MonthlyStateCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteBoundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 股票数据就绪报告本地写出器。
 * <p>
 * 在本地只读环境将 {@link StockDataReadinessReport} 输出为同 runId 的
 * {@code <runId>-summary.json} 与 {@code <runId>-summary.md}，使用临时文件 + 原子改名，
 * 不写生产数据库。JSON 与 Markdown 必须来自同一个不可变 Report。
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

    /**
     * 将报告模型转换为可序列化 Map。
     *
     * @param report 就绪报告模型
     * @return JSON 输出用的有序 Map
     */
    private Map<String, Object> toMap(StockDataReadinessReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", report.runId());
        map.put("generatedAt", report.generatedAt() == null ? null : report.generatedAt().toString());
        map.put("startInclusive", report.startInclusive() == null ? null : report.startInclusive().toString());
        map.put("endExclusive", report.endExclusive() == null ? null : report.endExclusive().toString());
        map.put("barBuildVersion", report.barBuildVersion());
        map.put("featureVersion", report.featureVersion());
        map.put("manifestHash", report.manifestHash());
        map.put("snapshot", snapshotToMap(report.snapshot()));
        return map;
    }

    /**
     * 将统计快照转换为可序列化 Map。
     *
     * @param snapshot 统计快照
     * @return 有序 Map
     */
    private Map<String, Object> snapshotToMap(StockDataReadinessSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stockCount", snapshot.stockCount());
        map.put("stockMinuteBoundaries", snapshot.stockMinuteBoundaries().stream()
                .map(this::boundaryToMap)
                .toList());
        map.put("minuteSourceDistribution", snapshot.minuteSourceDistribution());
        map.put("validMinuteCount", snapshot.validMinuteCount());
        map.put("duplicateMinuteGroupCount", snapshot.duplicateMinuteGroupCount());
        map.put("duplicateMinuteRedundantRowCount", snapshot.duplicateMinuteRedundantRowCount());
        map.put("invalidMinuteCount", snapshot.invalidMinuteCount());
        map.put("gapSegmentCount", snapshot.gapSegmentCount());
        map.put("maxGapMinutes", snapshot.maxGapMinutes());
        map.put("theoreticalBucketCount", snapshot.theoreticalBucketCount());
        map.put("barCount", snapshot.barCount());
        map.put("usableBarCount", snapshot.usableBarCount());
        map.put("unusableBarReasonCounts", snapshot.unusableBarReasonCounts());
        map.put("noMinuteFactBucketCount", snapshot.noMinuteFactBucketCount());
        map.put("featureCount", snapshot.featureCount());
        map.put("usableBarMissingFeatureCount", snapshot.usableBarMissingFeatureCount());
        map.put("featureOrphanCount", snapshot.featureOrphanCount());
        map.put("strategyReadyFeatureCount", snapshot.strategyReadyFeatureCount());
        map.put("notReadyFeatureReasonCounts", snapshot.notReadyFeatureReasonCounts());
        map.put("monthlyStateCounts", snapshot.monthlyStateCounts().stream()
                .map(this::monthlyStateToMap)
                .toList());
        map.put("roundStatusCounts", snapshot.roundStatusCounts());
        map.put("monthlyIncompleteReasonCounts", snapshot.monthlyIncompleteReasonCounts());
        map.put("roundVersionMismatchCount", snapshot.roundVersionMismatchCount());
        map.put("auditSettings", snapshot.auditSettings());
        return map;
    }

    /**
     * 将分钟边界转换为可序列化 Map。
     *
     * @param boundary 分钟边界
     * @return 有序 Map
     */
    private Map<String, Object> boundaryToMap(StockMinuteBoundary boundary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stocksId", boundary.stocksId());
        map.put("stocksShortname", boundary.stocksShortname());
        map.put("earliestMinute", boundary.earliestMinute() == null ? null : boundary.earliestMinute().toString());
        map.put("latestMinute", boundary.latestMinute() == null ? null : boundary.latestMinute().toString());
        map.put("minuteCount", boundary.minuteCount());
        return map;
    }

    /**
     * 将月度状态计数转换为可序列化 Map。
     *
     * @param count 月度状态计数
     * @return 有序 Map
     */
    private Map<String, Object> monthlyStateToMap(MonthlyStateCount count) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("effectiveMonth", count.effectiveMonth() == null ? null : count.effectiveMonth().toString());
        map.put("stateStatus", count.stateStatus());
        map.put("manualOverride", count.manualOverride());
        map.put("count", count.count());
        return map;
    }

    /**
     * 使用临时文件 + 原子改名写出文本内容，避免半成品文件被读取。
     *
     * @param target  目标文件路径
     * @param content 文件内容
     * @throws IOException 文件写出或移动失败时抛出
     */
    private void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 构建 Markdown 人工可读报告。
     *
     * @param report 就绪报告模型
     * @return Markdown 文本
     */
    private String buildMarkdown(StockDataReadinessReport report) {
        StockDataReadinessSnapshot s = report.snapshot();
        StringBuilder md = new StringBuilder();
        md.append("# VIP 股票数据就绪报告\n\n");
        md.append("- runId: ").append(report.runId()).append('\n');
        md.append("- 生成时刻: ").append(report.generatedAt()).append('\n');
        md.append("- 范围: [").append(report.startInclusive()).append(", ")
                .append(report.endExclusive()).append(")\n");
        md.append("- bar版本: ").append(report.barBuildVersion()).append('\n');
        md.append("- feature版本: ").append(report.featureVersion()).append('\n');
        md.append("- manifestHash: ").append(report.manifestHash()).append('\n');
        md.append("\n## 输入与版本\n\n");
        md.append("- 股票数: ").append(s.stockCount()).append('\n');
        md.append("- 分钟事实来源分布: ").append(s.minuteSourceDistribution()).append('\n');
        md.append("\n## 分钟质量\n\n");
        md.append("- 有效分钟: ").append(s.validMinuteCount()).append('\n');
        md.append("- 自然分钟重复组: ").append(s.duplicateMinuteGroupCount()).append('\n');
        md.append("- 自然分钟重复冗余行: ").append(s.duplicateMinuteRedundantRowCount()).append('\n');
        md.append("- 价格/总股数非法数: ").append(s.invalidMinuteCount()).append('\n');
        md.append("- 连续缺口段数: ").append(s.gapSegmentCount()).append('\n');
        md.append("- 最大缺口分钟数: ").append(s.maxGapMinutes()).append('\n');
        md.append("\n## 每股票分钟边界\n\n");
        md.append("|股票ID|简称|最早分钟|最晚分钟|有效自然分钟|\n");
        md.append("|---|---|---|---|---|\n");
        for (StockMinuteBoundary b : s.stockMinuteBoundaries()) {
            md.append('|').append(b.stocksId())
                    .append('|').append(b.stocksShortname())
                    .append('|').append(b.earliestMinute())
                    .append('|').append(b.latestMinute())
                    .append('|').append(b.minuteCount())
                    .append("|\n");
        }
        md.append("\n## bar\n\n");
        md.append("- 理论桶数: ").append(s.theoreticalBucketCount()).append('\n');
        md.append("- 存在bar: ").append(s.barCount()).append('\n');
        md.append("- 可用bar: ").append(s.usableBarCount()).append('\n');
        md.append("- 不可用原因: ").append(s.unusableBarReasonCounts()).append('\n');
        md.append("- 无分钟事实桶数: ").append(s.noMinuteFactBucketCount()).append('\n');
        md.append("\n## feature\n\n");
        md.append("- 当前版本feature: ").append(s.featureCount()).append('\n');
        md.append("- usable bar缺feature: ").append(s.usableBarMissingFeatureCount()).append('\n');
        md.append("- feature orphan: ").append(s.featureOrphanCount()).append('\n');
        md.append("- strategyReady: ").append(s.strategyReadyFeatureCount()).append('\n');
        md.append("- 未就绪原因: ").append(s.notReadyFeatureReasonCounts()).append('\n');
        md.append("\n## 月度状态\n\n");
        md.append("- DRAFT未完整原因: ").append(s.monthlyIncompleteReasonCounts()).append('\n');
        md.append("- 月度状态计数: ").append(s.monthlyStateCounts()).append('\n');
        md.append("\n## round\n\n");
        md.append("- 轮次状态计数: ").append(s.roundStatusCounts()).append('\n');
        md.append("- 版本不一致轮次数: ").append(s.roundVersionMismatchCount()).append('\n');
        md.append("\n## 审计\n\n");
        md.append("- 当前VIP_STOCK_*开关只读值: ").append(s.auditSettings()).append('\n');
        md.append("- 局限: 报告生成本身无法证明历史副作用增量，副作用delta仍须在生产运行前后用独立只读SQL比对。\n");
        return md.toString();
    }
}
