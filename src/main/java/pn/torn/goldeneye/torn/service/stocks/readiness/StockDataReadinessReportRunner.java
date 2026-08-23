package pn.torn.goldeneye.torn.service.stocks.readiness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.torn.stocks.readiness.StockDataReadinessQueryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.*;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mFeatureBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.replay.StockReplayReadOnlyGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票数据就绪报告本地只读运行器。
 * <p>
 * 由 AI 在本地订阅库追平后显式调用，不注册生产调度或 Bot 指令。所有统计在单一
 * {@code READ ONLY + REPEATABLE READ} 快照内加载，生成真实 JSON/Markdown 审核报告。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDataReadinessReportRunner {

    private final StockDataReadinessReportWriter writer;
    private final StockDataReadinessQueryDAO queryDao;
    private final StockReplayReadOnlyGuard readOnlyGuard;
    private final StockMarketClock marketClock;

    /**
     * 生成并输出一份指定范围的只读统计报告。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 生成的 summary.json 路径与完整不可变报告
     */
    public ReportRunResult run(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        if (startInclusive == null || endExclusive == null || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("数据就绪报告要求 startInclusive < endExclusive");
        }
        StockDataReadinessSnapshot snapshot = readOnlyGuard.inReadOnlyTransaction(
                status -> loadSnapshot(startInclusive, endExclusive));
        String runId = UUID.randomUUID().toString();
        LocalDateTime generatedAt = marketClock.now();
        String barBuildVersion = Stock15mBarBuildService.BUILD_VERSION;
        String featureVersion = Stock15mFeatureBuildService.FEATURE_VERSION;
        String manifestHash = buildManifestHash(
                startInclusive, endExclusive, barBuildVersion, featureVersion, snapshot);

        StockDataReadinessReport report = new StockDataReadinessReport(
                runId, generatedAt, startInclusive, endExclusive,
                barBuildVersion, featureVersion, manifestHash, snapshot);
        try {
            Path output = writer.write(Path.of(".hermes", "output", "vip-stock-readiness"), report);
            log.info("数据就绪报告已生成, runId={}, json={}", runId, output);
            return new ReportRunResult(output, report);
        } catch (Exception e) {
            throw new IllegalStateException("数据就绪报告生成失败", e);
        }
    }

    /**
     * 在只读事务回调内加载全部统计快照。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 完整统计快照
     */
    private StockDataReadinessSnapshot loadSnapshot(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        int stockCount = queryDao.countStocks();
        List<StockMinuteBoundary> boundaries = queryDao.selectStockMinuteBoundaries(startInclusive, endExclusive);
        Map<String, Long> sourceDistribution = toNameCountMap(
                queryDao.selectMinuteSourceDistribution(startInclusive, endExclusive)
                        .stream()
                        .map(row -> new NameCount(row.source(), row.count()))
                        .toList());
        long validMinuteCount = queryDao.selectValidMinuteCount(startInclusive, endExclusive);
        long duplicateMinuteGroupCount = queryDao.selectDuplicateMinuteGroupCount(startInclusive, endExclusive);
        long duplicateMinuteRedundantRowCount =
                queryDao.selectDuplicateMinuteRedundantRowCount(startInclusive, endExclusive);
        long invalidMinuteCount = queryDao.selectInvalidMinuteCount(startInclusive, endExclusive);
        GapSummary gapSummary = queryDao.selectGapSummary(startInclusive, endExclusive);

        String barBuildVersion = Stock15mBarBuildService.BUILD_VERSION;
        String featureVersion = Stock15mFeatureBuildService.FEATURE_VERSION;
        long barCount = queryDao.selectBarCount(startInclusive, endExclusive, barBuildVersion);
        long usableBarCount = queryDao.selectUsableBarCount(startInclusive, endExclusive, barBuildVersion);
        Map<String, Long> unusableBarReasonCounts = toNameCountMap(
                queryDao.selectUnusableBarReasonCounts(startInclusive, endExclusive, barBuildVersion));
        long featureCount = queryDao.selectFeatureCount(startInclusive, endExclusive, featureVersion);
        long usableBarMissingFeatureCount = queryDao.selectUsableBarMissingFeatureCount(
                startInclusive, endExclusive, barBuildVersion, featureVersion);
        long featureOrphanCount = queryDao.selectFeatureOrphanCount(
                startInclusive, endExclusive, barBuildVersion, featureVersion);
        long strategyReadyFeatureCount = queryDao.selectStrategyReadyFeatureCount(
                startInclusive, endExclusive, featureVersion);
        Map<String, Long> notReadyFeatureReasonCounts = toNameCountMap(
                queryDao.selectNotReadyFeatureReasonCounts(startInclusive, endExclusive, featureVersion));

        List<MonthlyStateCount> monthlyStateCounts =
                queryDao.selectMonthlyStateCounts(startInclusive, endExclusive);
        Map<String, Long> monthlyIncompleteReasonCounts = toNameCountMap(
                queryDao.selectMonthlyIncompleteReasonCounts(startInclusive, endExclusive));
        Map<String, Long> roundStatusCounts = toNameCountMap(
                queryDao.selectRoundStatusCounts(startInclusive, endExclusive)
                        .stream()
                        .map(row -> new NameCount(row.roundStatus(), row.count()))
                        .toList());
        long roundVersionMismatchCount = queryDao.selectRoundVersionMismatchCount(
                startInclusive, endExclusive, barBuildVersion, featureVersion);
        Map<String, String> auditSettings = new LinkedHashMap<>();
        for (SettingValue setting : queryDao.selectVipStockSettings()) {
            auditSettings.put(setting.settingKey(), setting.settingValue());
        }

        long bucketMinutes = Duration.between(startInclusive, endExclusive).toMinutes()
                / Stock15mBarBuildService.BUCKET_MINUTES;
        long theoreticalBucketCount = bucketMinutes * stockCount;
        long noMinuteFactBucketCount = Math.max(0, theoreticalBucketCount - barCount);

        return new StockDataReadinessSnapshot(
                stockCount, boundaries, sourceDistribution,
                validMinuteCount, duplicateMinuteGroupCount, duplicateMinuteRedundantRowCount,
                invalidMinuteCount, gapSummary.gapSegmentCount(), gapSummary.maxGapMinutes(),
                theoreticalBucketCount, barCount, usableBarCount, unusableBarReasonCounts,
                noMinuteFactBucketCount, featureCount, usableBarMissingFeatureCount,
                featureOrphanCount, strategyReadyFeatureCount, notReadyFeatureReasonCounts,
                monthlyStateCounts, monthlyIncompleteReasonCounts, roundStatusCounts,
                roundVersionMismatchCount, auditSettings);
    }

    /**
     * 将分组计数列表转换为有序 Map。
     *
     * @param rows 分组计数
     * @return 有序 Map
     */
    private Map<String, Long> toNameCountMap(List<NameCount> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (NameCount row : rows) {
            result.put(row.name(), row.count());
        }
        return result;
    }

    /**
     * 按固定字段顺序 canonicalize 后计算 SHA-256。
     *
     * @param startInclusive  起始时间（含）
     * @param endExclusive    结束时间（不含）
     * @param barBuildVersion bar 构建版本
     * @param featureVersion  feature 版本
     * @param snapshot        统计快照
     * @return SHA-256 十六进制小写摘要
     */
    private String buildManifestHash(LocalDateTime startInclusive, LocalDateTime endExclusive,
                                     String barBuildVersion, String featureVersion,
                                     StockDataReadinessSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append("range=").append(startInclusive).append('|').append(endExclusive).append('\n');
            sb.append("versions=").append(barBuildVersion).append('|').append(featureVersion).append('\n');
            sb.append("stockCount=").append(snapshot.stockCount()).append('\n');
            sb.append("boundaries=").append(snapshot.stockMinuteBoundaries().stream()
                    .sorted(Comparator.comparing(StockMinuteBoundary::stocksId))
                    .map(b -> b.stocksId() + ":" + b.earliestMinute() + ":" + b.latestMinute() + ":" + b.minuteCount())
                    .toList()).append('\n');
            sb.append("minuteSource=").append(snapshot.minuteSourceDistribution()).append('\n');
            sb.append("minutes=").append(snapshot.validMinuteCount()).append(',')
                    .append(snapshot.duplicateMinuteGroupCount()).append(',')
                    .append(snapshot.duplicateMinuteRedundantRowCount()).append(',')
                    .append(snapshot.invalidMinuteCount()).append(',')
                    .append(snapshot.gapSegmentCount()).append(',')
                    .append(snapshot.maxGapMinutes()).append('\n');
            sb.append("bars=").append(snapshot.theoreticalBucketCount()).append(',')
                    .append(snapshot.barCount()).append(',')
                    .append(snapshot.usableBarCount()).append(',')
                    .append(snapshot.unusableBarReasonCounts()).append(',')
                    .append(snapshot.noMinuteFactBucketCount()).append('\n');
            sb.append("features=").append(snapshot.featureCount()).append(',')
                    .append(snapshot.usableBarMissingFeatureCount()).append(',')
                    .append(snapshot.featureOrphanCount()).append(',')
                    .append(snapshot.strategyReadyFeatureCount()).append(',')
                    .append(snapshot.notReadyFeatureReasonCounts()).append('\n');
            sb.append("months=").append(snapshot.monthlyStateCounts().stream()
                            .sorted(Comparator.comparing(MonthlyStateCount::effectiveMonth)
                                    .thenComparing(MonthlyStateCount::stateStatus)
                                    .thenComparing(MonthlyStateCount::manualOverride))
                            .toList()).append(',')
                    .append(snapshot.monthlyIncompleteReasonCounts()).append('\n');
            sb.append("rounds=").append(snapshot.roundStatusCounts()).append(',')
                    .append(snapshot.roundVersionMismatchCount()).append('\n');
            sb.append("settings=").append(snapshot.auditSettings());
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 报告运行结果。
     *
     * @param path   生成的 summary.json 路径
     * @param report 完整不可变报告
     */
    public record ReportRunResult(Path path, StockDataReadinessReport report) {
    }
}
