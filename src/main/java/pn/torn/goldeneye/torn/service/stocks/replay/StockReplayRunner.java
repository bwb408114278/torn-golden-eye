package pn.torn.goldeneye.torn.service.stocks.replay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mFeatureBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockRoundTransactionService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 回放编排器: 校验请求、校验只读会话、只读加载窗口数据、运行轨道引擎、汇总并写出产物。
 *
 * <p>触发方式: 仅通过测试或显式调用 {@link #run(StockReplayRequest)};不注册任何启动入口。
 * 相同输入生成相同 {@code runId} 与完全一致的产物内容(确定性)。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Slf4j
@Component
public class StockReplayRunner {

    /**
     * 默认产物输出根目录。
     */
    public static final String DEFAULT_OUTPUT_ROOT = ".hermes/output/vip-stock-replay";
    /**
     * 年化回放标记。
     */
    public static final String ANNUALIZED_BACKTEST_MARKER = "SHORT_HISTORY_ANNUALIZED_BACKTEST";
    /**
     * 需要正式生产轨道的派生研究轨道。
     */
    private static final Set<StockReplayTrackEnum> DERIVED_TRACKS = Set.of(
            StockReplayTrackEnum.UNLIMITED_SHADOW,
            StockReplayTrackEnum.REJECTION_OBSERVATION,
            StockReplayTrackEnum.DYNAMIC_SELL_SHADOW,
            StockReplayTrackEnum.HIGH_RISK_OBSERVATION,
            StockReplayTrackEnum.RAW_BUY_CONTROL);

    private final StockReplayInputLoader inputLoader;
    private final StockReplayReadOnlyGuard readOnlyGuard;
    private final StockReplayResultWriter resultWriter;

    /**
     * 构造回放编排器。
     *
     * @param inputLoader   只读输入加载器
     * @param readOnlyGuard 只读事务守卫
     * @param resultWriter  产物写入器
     */
    public StockReplayRunner(StockReplayInputLoader inputLoader,
                             StockReplayReadOnlyGuard readOnlyGuard,
                             StockReplayResultWriter resultWriter) {
        this.inputLoader = inputLoader;
        this.readOnlyGuard = readOnlyGuard;
        this.resultWriter = resultWriter;
    }

    /**
     * 运行一次隔离回放并写出产物。
     *
     * @param request 回放请求
     * @return 内存回放结果(与产物一致)
     * @throws IllegalArgumentException 请求参数非法时抛出
     * @throws IllegalStateException    只读会话校验失败、已完成同代际拒绝覆盖或写入失败时抛出
     */
    public StockReplayResult run(StockReplayRequest request) {
        StockReplayRequest normalized = normalize(request);
        String runId = computeRunId(normalized);
        log.info("隔离回放开始: runId={}, range=[{}, {}], tracks={}",
                runId, normalized.startTime(), normalized.endTime(),
                normalized.tracks().stream().map(StockReplayTrackEnum::getCode).sorted().toList());
        try {
            readOnlyGuard.verifyReadOnlySession();
            StockReplayWindowData windowData = inputLoader.load(normalized);
            StockReplayContext context = new StockReplayContext(normalized, windowData);

            List<StockReplayTrackEnum> formalTracks = normalized.tracks().stream()
                    .filter(StockReplayTrackEnum::isFormal)
                    .toList();
            Map<String, List<StockReplayTrade>> trades = new java.util.LinkedHashMap<>();
            Map<String, List<StockReplayRejection>> rejections = new java.util.LinkedHashMap<>();
            Map<String, List<StockReplayEquityPoint>> equityPoints = new java.util.LinkedHashMap<>();
            Map<String, StockReplaySummary.TrackSummary> summaries = new java.util.LinkedHashMap<>();
            StockReplaySummary.DynamicSellSummary dynamicSell = null;

            for (StockReplayTrackEnum formalTrack : formalTracks) {
                StockReplayEngine engine = new StockReplayEngine(formalTrack, runId, context);
                engine.run();
                merge(engine.tradesByTrack(), trades);
                merge(engine.rejectionsByTrack(), rejections);
                merge(engine.equityByTrack(), equityPoints);
                Map<String, StockReplaySummary.TrackSummary> engineSummaries =
                        engine.buildSummaries(normalizedWindowDays(normalized));
                summaries.putAll(engineSummaries);
                if (formalTrack == StockReplayTrackEnum.FORMAL_20E) {
                    dynamicSell = engine.dynamicSellSummary();
                }
            }

            long windowDays = normalizedWindowDays(normalized);
            StockReplaySourceManifest sourceManifest = windowData.sourceManifest();
            StockReplaySummary summary = buildSummary(
                    runId, normalized, windowDays, summaries, dynamicSell, sourceManifest, null);
            StockReplayResult result = new StockReplayResult(
                    runId, summary,
                    flatten(trades), flatten(rejections), flatten(equityPoints));
            resultWriter.writeCompleted(runId, normalized.outputRootDir(), result);
            log.info("隔离回放完成: runId={}, 产物目录={}, sourceManifestHash={}",
                    runId, normalized.outputRootDir() + "/" + runId,
                    sourceManifest == null ? null : sourceManifest.sha256());
            return result;
        } catch (RuntimeException e) {
            log.error("隔离回放失败: runId={}, error={}", runId, e.getMessage(), e);
            writeFailedSummary(normalized, runId, e);
            throw e;
        }
    }

    private void writeFailedSummary(StockReplayRequest normalized, String runId, RuntimeException error) {
        try {
            long windowDays = normalizedWindowDays(normalized);
            StockReplaySummary failed = buildSummary(
                    runId, normalized, windowDays, Map.of(), null, null, error.getMessage());
            String attemptId = UUID.randomUUID().toString();
            resultWriter.writeFailed(runId, attemptId, normalized.outputRootDir(),
                    new StockReplayResult(runId, failed, List.of(), List.of(), List.of()));
        } catch (Exception writeError) {
            log.warn("失败摘要写出失败: runId={}, error={}", runId, writeError.getMessage());
        }
    }

    /**
     * 归一化请求: 对齐15分钟桶、填充默认值、校验处理模式契约、自动补充正式生产轨道。
     * <p>
     * 处理模式契约: 未显式提供模式时归一化为{@code ONLINE_BASELINE}且{@code recoveredAt=null};
     * {@code ONLINE_BASELINE}携带{@code recoveredAt}、{@code RESTART_STRESS}缺失{@code recoveredAt}、
     * 或{@code recoveredAt}早于窗口起点时均fail-fast。
     *
     * @param request 原始请求
     * @return 归一化请求
     * @throws IllegalArgumentException 模式契约或窗口参数非法时抛出
     */
    static StockReplayRequest normalize(StockReplayRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("回放请求不能为空");
        }
        LocalDateTime start = Stock15mBarBuildService.alignToBucket(request.startTime());
        LocalDateTime end = Stock15mBarBuildService.alignToBucket(request.endTime());
        StockReplayInputLoader.validateWindow(start, end);
        String outputRoot = request.outputRootDir() == null || request.outputRootDir().isBlank()
                ? DEFAULT_OUTPUT_ROOT : request.outputRootDir();
        Set<StockReplayTrackEnum> tracks = new LinkedHashSet<>(request.tracks());
        if (tracks.isEmpty()) {
            tracks.addAll(List.of(StockReplayTrackEnum.values()));
        }
        if (tracks.stream().anyMatch(DERIVED_TRACKS::contains)
                && !tracks.contains(StockReplayTrackEnum.FORMAL_20E)) {
            tracks.add(StockReplayTrackEnum.FORMAL_20E);
        }
        StockReplayProcessingModeEnum mode = request.processingMode();
        LocalDateTime recoveredAt = request.recoveredAt();
        if (mode == null) {
            if (recoveredAt != null) {
                throw new IllegalArgumentException("未指定处理模式时不允许携带recoveredAt,仅RESTART_STRESS使用");
            }
            mode = StockReplayProcessingModeEnum.ONLINE_BASELINE;
        }
        validateProcessingMode(mode, recoveredAt, start);
        return new StockReplayRequest(start, end,
                request.barBuildVersion() == null ? Stock15mBarBuildService.BUILD_VERSION
                        : request.barBuildVersion(),
                request.featureVersion() == null ? Stock15mFeatureBuildService.FEATURE_VERSION
                        : request.featureVersion(),
                request.buyRuleVersion() == null ? StockRoundTransactionService.BUY_RULE_VERSION
                        : request.buyRuleVersion(),
                request.sellRuleVersion() == null ? "1.0.0" : request.sellRuleVersion(),
                tracks, outputRoot, mode, recoveredAt);
    }

    /**
     * 校验处理模式与恢复时刻契约。
     *
     * @param mode        处理模式(已归一化,非空)
     * @param recoveredAt 恢复时刻
     * @param start       窗口起点(含)
     * @throws IllegalArgumentException 模式与时间字段不匹配或恢复时刻早于窗口起点时抛出
     */
    private static void validateProcessingMode(StockReplayProcessingModeEnum mode,
                                               LocalDateTime recoveredAt,
                                               LocalDateTime start) {
        if (mode == StockReplayProcessingModeEnum.ONLINE_BASELINE
                && recoveredAt != null) {
            throw new IllegalArgumentException(
                    "ONLINE_BASELINE不允许携带recoveredAt,实际=" + recoveredAt);
        }
        if (mode != StockReplayProcessingModeEnum.RESTART_STRESS) {
            return;
        }
        if (recoveredAt == null) {
            throw new IllegalArgumentException("RESTART_STRESS必须指定recoveredAt");
        }
        if (recoveredAt.isBefore(start)) {
            throw new IllegalArgumentException(
                    "recoveredAt不得早于需要按该时刻处理的回放轮次, recoveredAt=" + recoveredAt
                            + ", start=" + start);
        }
    }

    /**
     * 确定性runId: 输入参数、处理模式、恢复时刻与轨道集合的SHA-256前12位。
     *
     * @param request 归一化请求
     * @return runId
     */
    static String computeRunId(StockReplayRequest request) {
        String payload = String.join("|",
                request.startTime().toString(),
                request.endTime().toString(),
                request.barBuildVersion(),
                request.featureVersion(),
                request.buyRuleVersion(),
                request.sellRuleVersion(),
                request.outputRootDir(),
                request.tracks().stream().map(StockReplayTrackEnum::getCode).sorted().collect(Collectors.joining(",")),
                String.valueOf(request.processingMode()),
                request.recoveredAt() == null ? "" : request.recoveredAt().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "replay-" + hex.substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    private static long normalizedWindowDays(StockReplayRequest request) {
        return ChronoUnit.DAYS.between(request.startTime().toLocalDate(),
                request.endTime().toLocalDate()) + 1;
    }

    private static <T> void merge(Map<String, List<T>> source, Map<String, List<T>> target) {
        for (Map.Entry<String, List<T>> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    private static <T> List<T> flatten(Map<String, List<T>> byTrack) {
        List<T> result = new ArrayList<>();
        for (StockReplayTrackEnum track : StockReplayTrackEnum.values()) {
            List<T> values = byTrack.get(track.getCode());
            if (values != null) {
                result.addAll(values);
            }
        }
        return result;
    }

    private static StockReplaySummary buildSummary(String runId,
                                                   StockReplayRequest request,
                                                   long windowDays,
                                                   Map<String, StockReplaySummary.TrackSummary> summaries,
                                                   StockReplaySummary.DynamicSellSummary dynamicSell,
                                                   StockReplaySourceManifest sourceManifest,
                                                   String error) {
        List<StockReplaySummary.TrackSummary> orderedTracks = new ArrayList<>();
        for (StockReplayTrackEnum track : StockReplayTrackEnum.values()) {
            if (!request.tracks().contains(track)) {
                continue;
            }
            StockReplaySummary.TrackSummary summary = summaries.get(track.getCode());
            if (summary == null && track == StockReplayTrackEnum.DYNAMIC_SELL_SHADOW && dynamicSell != null) {
                summary = new StockReplaySummary.TrackSummary(
                        track.getCode(), track.getDisplayName(), 0, null, 0, 0, 0,
                        null, null, null, null, null, 0, null,
                        0, StockReplaySummary.emptyReasonMap(), 0,
                        StockReplaySummary.emptyReasonMap(), null, 0, dynamicSell);
            }
            if (summary != null) {
                orderedTracks.add(summary);
            }
        }
        String status = error == null ? "COMPLETED" : "FAILED";
        return new StockReplaySummary(runId, status, ANNUALIZED_BACKTEST_MARKER,
                request.startTime(), request.endTime(), windowDays,
                request.barBuildVersion(), request.featureVersion(),
                request.buyRuleVersion(), request.sellRuleVersion(),
                request.processingMode(), request.recoveredAt(),
                sourceManifest, orderedTracks, error);
    }
}
