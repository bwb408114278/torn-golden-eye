package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 股票历史重建服务 - 从现有torn_stocks_history按15分钟规则重建历史bar和特征
 * <p>
 * 用于冷启动补偿与历史回放:按15分钟桶逐桶重建bar与策略特征,
 * 并维护 {@link TornStockMarketRoundDO} 轮次状态(BUILDING_BAR -> BUILDING_FEATURE -> READY),
 * 作为策略回放与审计的主线索。复用 {@link Stock15mBarBuildService} 与
 * {@link Stock15mFeatureBuildService} 的生产构建逻辑,不维护两套逻辑。
 *
 * <h3>完整数据义务</h3>
 * 一个桶仅在同时满足下列条件时允许完整跳过(不再只凭bar存在判定):
 * <ol>
 *   <li>当前 {@code buildVersion} 的bar完整存在;</li>
 *   <li>当前 {@code featureVersion} 的feature完整存在,并与bar按{@code stocksId + barStartTime}一一对应;</li>
 *   <li>对应{@code roundTime}存在轮次;</li>
 *   <li>轮次状态为{@code READY}或{@code COMPLETED};</li>
 *   <li>round中的bar/feature版本与当前构建版本一致。</li>
 * </ol>
 * {@code PENDING}、{@code BUILDING_BAR}、{@code BUILDING_FEATURE}、{@code WAITING_DATA}与
 * {@code FAILED_RETRYABLE}均不是完整状态,按实际缺口恢复至{@code READY};
 * {@code FAILED_FINAL}保留终态和错误事实,不做启动补偿自动重开。
 *
 * <h3>恢复职责边界</h3>
 * 重建服务只修复数据层到{@code READY},不调用 {@link StockRoundTransactionService},
 * 不创建交易、Shadow、通知、冷却或月度状态;只有既有调度器消费{@code READY}轮次并在
 * 组合事务成功后置为{@code COMPLETED}。任一桶修复抛异常时,将可重试round标为
 * {@code FAILED_RETRYABLE}并向上抛出,由调度层维持"阻断同次月度重算/自动确认和新入场"的
 * fail-closed行为。
 *
 * <h3>回填修复入口的状态隔离</h3>
 * 普通启动补偿/完整性恢复入口({@code rebuildHistory}系)落{@code READY}并允许生产调度器
 * 继续消费;Tornsy 回填修复入口({@code repairBackfilledHistory})只落数据修复终态
 * {@code REPAIRED_DATA_ONLY}(或保留 COMPLETED/FAILED_FINAL 终态),{@code READY}语义
 * 不得扩散到回填入口,回填修复的轮次永不进入策略事务。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockHistoryRebuildService {

    /**
     * 进度日志输出间隔(每处理多少个桶输出一次info日志)
     */
    private static final int PROGRESS_LOG_INTERVAL = 10;
    /**
     * feature 后向重算单段最大跨度(天): 每段最多 96 个 15 分钟桶
     */
    private static final int FEATURE_SEGMENT_DAYS = 1;

    private final Stock15mBarBuildService barBuildService;
    private final Stock15mFeatureBuildService featureBuildService;
    private final TornStockMarketRoundDAO roundDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final TornStockMarketBar15mDAO bar15mDao;
    private final TornStockStrategyFeature15mDAO feature15mDao;
    private final StockMarketRoundFactory roundFactory;
    private final StockMarketClock marketClock;

    // ==================== 公开入口 ====================

    /**
     * 从指定起始时间到结束时间,按15分钟桶逐桶重建历史bar与特征
     * <p>
     * 自动将startTime与endTime对齐到15分钟桶边界。对每个桶先按完整数据义务判定:
     * 完整桶直接跳过,缺口桶按实际缺口恢复至{@code READY}。任意桶修复异常立即抛出,
     * 由调度层决定是否阻断同次月度下游。
     *
     * @param startTime 重建起始时间(方法内部会自动对齐到桶边界,含)
     * @param endTime   重建结束时间(应为已结束桶的边界,方法内部会自动对齐,不含)
     * @return 实际重建(非跳过)的桶数量
     */
    public int rebuildHistory(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime start = Stock15mBarBuildService.alignToBucket(startTime);
        LocalDateTime end = Stock15mBarBuildService.alignToBucket(endTime);

        if (!start.isBefore(end)) {
            log.warn("重建区间无效, start={}, end={}, 起始时间需早于结束时间", start, end);
            return 0;
        }

        long totalBuckets = calculateBucketCount(start, end);
        log.info("开始历史重建, start={}, end={}, 预计{}个桶", start, end, totalBuckets);

        int rebuiltCount = 0;
        int processedCount = 0;
        LocalDateTime current = start;
        while (current.isBefore(end)) {
            BucketRepairResult result = evaluateBucket(current);
            switch (result.action()) {
                case COMPLETE -> log.debug("桶{}完整(bar/feature/round及版本一致), 跳过重建", current);
                case SKIP_FAILED_FINAL -> log.info("桶{}为FAILED_FINAL终态, 保留失败事实并跳过, error={}",
                        current, result.round().getErrorMessage());
                case REBUILD_DATA -> {
                    rebuildSingleBucket(current);
                    rebuiltCount++;
                }
                case REPAIR_FEATURE -> {
                    repairFeatureBucket(current, result.bars(), result.round());
                    rebuiltCount++;
                }
                case REPAIR_ROUND, RETRY_ROUND -> {
                    restoreRoundReady(current, result.bars(), result.features(), result.round());
                    rebuiltCount++;
                }
            }
            processedCount++;

            if (processedCount % PROGRESS_LOG_INTERVAL == 0) {
                log.info("重建进度: 已处理{}/{}个桶, 实际重建{}个, 当前桶={}",
                        processedCount, totalBuckets, rebuiltCount, current);
            }

            current = current.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        }

        log.info("历史重建完成, start={}, end={}, 预计{}个桶, 实际重建{}个", start, end, totalBuckets, rebuiltCount);
        return rebuiltCount;
    }

    /**
     * 从最后一个已完成轮次之后开始补算至指定结束时间
     * <p>
     * 查询最后一个COMPLETED轮次的roundTime,从其下一15分钟桶开始补算。
     * 若不存在已完成轮次,则从torn_stocks_history最早记录所在桶开始。
     *
     * @param endTime 重建结束时间(应为已结束桶的边界)
     * @return 实际重建的桶数量
     */
    public int rebuildFromLastCompleted(LocalDateTime endTime) {
        LocalDateTime alignedEnd = Stock15mBarBuildService.alignToBucket(endTime);
        LocalDateTime startTime = determineRebuildStartTime();
        if (startTime == null) {
            log.warn("无法确定重建起始时间, 退出补算");
            return 0;
        }

        if (!startTime.isBefore(alignedEnd)) {
            log.info("无需补算, 起始时间={}, 结束时间={}", startTime, alignedEnd);
            return 0;
        }

        log.info("从最后已完成轮次之后开始补算, start={}, end={}", startTime, alignedEnd);
        return rebuildHistory(startTime, alignedEnd);
    }

    /**
     * 查询torn_stocks_history表中最早的reg_date_time
     * <p>
     * 用于在没有已完成轮次时确定重建起点。
     *
     * @return 最早历史记录时间, 表为空时返回null
     */
    public LocalDateTime findEarliestHistoryTime() {
        TornStocksHistoryDO earliest = stocksHistoryDao.lambdaQuery()
                .select(TornStocksHistoryDO::getRegDateTime)
                .orderByAsc(TornStocksHistoryDO::getRegDateTime)
                .last("LIMIT 1")
                .one();
        if (earliest == null || earliest.getRegDateTime() == null) {
            log.warn("torn_stocks_history表无历史记录, 无法确定最早时间");
            return null;
        }
        LocalDateTime earliestTime = earliest.getRegDateTime();
        log.info("torn_stocks_history最早记录时间: {}", earliestTime);
        return earliestTime;
    }

    // ==================== 回填驱动数据修复入口 ====================

    /**
     * 回填驱动的派生数据修复：受影响桶强制 bar 重建 + 后向 30 天 feature 分段重算。
     * <p>
     * 仅修复数据层，不调用 {@link StockRoundTransactionService}、不创建历史交易、消息、
     * Shadow、冷却或月度状态。处理顺序：
     * <ol>
     *   <li>对每个受影响桶:无论 bar 是否存在都强制 UPSERT bar,并重建 feature;
     *       round 不存在时幂等创建后、其余未完成状态统一写数据修复终态
     *       {@code REPAIRED_DATA_ONLY};已完成的 round 保持 {@code COMPLETED},
     *       {@code FAILED_FINAL} 保持终态,均只更新 bar/feature 不改状态;</li>
     *   <li>对 {@code [earliestAffected, featureRebuildEndExclusive)} 按 15 分钟因果顺序
     *       遍历,只要目标桶存在当前 build version 的 bar 就强制 UPSERT feature,
     *       无 bar 的桶仅记录/跳过,绝不伪造 bar;</li>
     *   <li>后向重算按连续 1 天(96 桶)分段执行,每 10 桶记录一次进度;
     *       任一段失败立即停止后续段并向上抛出,由调度层保持"实验未完成"语义。</li>
     * </ol>
     * {@code REPAIRED_DATA_ONLY} 不属于生产策略待处理队列:生产轮次消费白名单
     * {@code selectPendingRoundsUpTo} 绝不返回该状态,历史回填修复永不触发策略事务。
     *
     * @param affectedBuckets            实际插入分钟所属的受影响桶集合(已对齐)
     * @param featureRebuildEndExclusive 后向 feature 重算结束时间(不含,应为
     *                                   {@code alignToBucket(latestAffected) + 30天 + 15分钟})
     * @param backfillRunId              本次回填运行标识(仅用于进度日志)
     * @return 修复统计结果
     */
    public BackfillRepairResult repairBackfilledHistory(Collection<LocalDateTime> affectedBuckets,
                                                        LocalDateTime featureRebuildEndExclusive,
                                                        String backfillRunId) {
        List<LocalDateTime> sortedBuckets = affectedBuckets.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (sortedBuckets.isEmpty()) {
            log.info("历史回填修复-无受影响桶, 跳过派生数据修复, runId={}", backfillRunId);
            return new BackfillRepairResult(0, 0, 0, 0);
        }

        LocalDateTime earliestAffected = sortedBuckets.getFirst();
        LocalDateTime featureEnd = Stock15mBarBuildService.alignToBucket(featureRebuildEndExclusive);
        log.info("历史回填修复-开始, runId={}, affectedBucketCount={}, earliestAffected={}, "
                        + "featureRebuildEndExclusive={}", backfillRunId, sortedBuckets.size(),
                earliestAffected, featureEnd);

        int[] affectedStats = repairAffectedBuckets(sortedBuckets);
        int[] featureStats = recomputeFeaturesBySegments(earliestAffected, featureEnd, backfillRunId,
                affectedStats[0]);

        BackfillRepairResult result = new BackfillRepairResult(
                affectedStats[0], affectedStats[1], featureStats[0], featureStats[1]);
        log.info("历史回填修复-完成, runId={}, forcedBarBuckets={}, dataOnlyRoundCount={}, "
                        + "recomputedFeatureBuckets={}, skippedNoBarBuckets={}, rebuiltBucketCount={}",
                backfillRunId, result.forcedBarBuckets(), result.dataOnlyRoundCount(),
                result.recomputedFeatureBuckets(), result.skippedNoBarBuckets(), result.rebuiltBucketCount());
        return result;
    }

    /**
     * 修复受影响桶：强制重建 bar 与 feature,并按既有轮次状态写入数据修复终态。
     * <p>
     * 每个受影响桶无论 bar 是否已存在都调用 {@link Stock15mBarBuildService#buildBars(LocalDateTime)}
     * 强制 UPSERT,使新回填的分钟事实合并进既有 bar;随后重建 feature;
     * round 状态处理:{@code COMPLETED} 与 {@code FAILED_FINAL} 保持既有终态
     * (只更新 bar/feature),其余情况(含不存在)统一落 {@code REPAIRED_DATA_ONLY},
     * 严禁写入生产消费语义的 {@code READY}。
     *
     * @param affectedBuckets 受影响桶列表(已排序去重)
     * @return {@code [forcedBarBuckets, dataOnlyRoundCount]}
     */
    private int[] repairAffectedBuckets(List<LocalDateTime> affectedBuckets) {
        int forcedBarBuckets = 0;
        int dataOnlyRoundCount = 0;
        for (LocalDateTime bucket : affectedBuckets) {
            LocalDateTime now = marketClock.now();
            List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(bucket);
            if (!bars.isEmpty()) {
                forcedBarBuckets++;
            } else {
                log.warn("历史回填修复-受影响桶强制重建bar为空(无分钟采样), bucket={}", bucket);
            }
            List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(bucket);
            if (markRoundDataRepaired(bucket, now, bars.size(), features.size())) {
                dataOnlyRoundCount++;
            }
        }
        log.info("历史回填修复-受影响桶处理完成, forcedBarBuckets={}, dataOnlyRoundCount={}",
                forcedBarBuckets, dataOnlyRoundCount);
        return new int[]{forcedBarBuckets, dataOnlyRoundCount};
    }

    /**
     * 将指定桶的轮次落为回填数据修复终态 {@code REPAIRED_DATA_ONLY}。
     * <p>
     * 桶存在 {@code COMPLETED} 或 {@code FAILED_FINAL} 终态轮次时保留既有状态与失败事实,
     * 不改写轮次;其余情况下复用或幂等创建轮次并写 {@code REPAIRED_DATA_ONLY},
     * 保证回填修复的轮次永不进入生产策略消费队列。
     *
     * @param bucket       桶开始时间
     * @param now          本次修复审计时间
     * @param barCount     bar 数
     * @param featureCount feature 数
     * @return true 表示已写入数据修复终态;false 表示保留既有 COMPLETED/FAILED_FINAL 终态
     */
    private boolean markRoundDataRepaired(LocalDateTime bucket, LocalDateTime now,
                                          int barCount, int featureCount) {
        TornStockMarketRoundDO round = roundDao.selectByRoundTime(bucket);
        if (round != null) {
            String status = round.getRoundStatus();
            if (StockRoundStatusEnum.COMPLETED.getCode().equals(status)) {
                log.info("历史回填修复-桶{}为COMPLETED终态, 仅更新bar/feature, 保留轮次状态", bucket);
                return false;
            }
            if (StockRoundStatusEnum.FAILED_FINAL.getCode().equals(status)) {
                log.info("历史回填修复-桶{}为FAILED_FINAL终态, 保留失败事实, 不自动恢复", bucket);
                return false;
            }
        }
        TornStockMarketRoundDO target = round != null ? round : createRound(bucket, now);
        target.setRoundStatus(StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
        target.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        target.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        target.setExpectedStockCount(barCount);
        target.setUsableStockCount(featureCount);
        if (target.getStartedAt() == null) {
            target.setStartedAt(now);
        }
        target.setCompletedAt(now);
        roundDao.updateById(target);
        return true;
    }

    /**
     * 按连续 1 天区间分段后向重算 feature。
     * <p>
     * 从 earliestAffected 到 featureRebuildEndExclusive,每段最多 96 个桶;
     * 段失败时异常向上抛出,停止本次后续段。
     *
     * @param startInclusive   起始桶(含)
     * @param endExclusive     结束桶(不含)
     * @param backfillRunId    回填运行标识
     * @param forcedBarBuckets 已强制重建的 bar 桶数(仅用于进度日志)
     * @return {@code [recomputedFeatureBuckets, skippedNoBarBuckets]}
     */
    private int[] recomputeFeaturesBySegments(LocalDateTime startInclusive, LocalDateTime endExclusive,
                                              String backfillRunId, int forcedBarBuckets) {
        int recomputed = 0;
        int skippedNoBar = 0;
        int segmentNo = 0;
        LocalDateTime segmentStart = startInclusive;
        while (segmentStart.isBefore(endExclusive)) {
            LocalDateTime segmentEnd = segmentStart.plusDays(FEATURE_SEGMENT_DAYS);
            if (segmentEnd.isAfter(endExclusive)) {
                segmentEnd = endExclusive;
            }
            segmentNo++;
            RecomputeSegmentStats stats = recomputeFeatureSegment(
                    segmentStart, segmentEnd, backfillRunId, segmentNo, forcedBarBuckets);
            recomputed += stats.recomputed();
            skippedNoBar += stats.skippedNoBar();
            segmentStart = segmentEnd;
        }
        return new int[]{recomputed, skippedNoBar};
    }

    /**
     * 重算单个 1 天 feature 段,每 10 桶输出一次进度日志。
     *
     * @param segmentStart     段起始桶(含)
     * @param segmentEnd       段结束桶(不含)
     * @param backfillRunId    回填运行标识
     * @param segmentNo        段序号
     * @param forcedBarBuckets 已强制重建的 bar 桶数(仅用于进度日志)
     * @return 段内统计
     */
    private RecomputeSegmentStats recomputeFeatureSegment(LocalDateTime segmentStart, LocalDateTime segmentEnd,
                                                          String backfillRunId, int segmentNo,
                                                          int forcedBarBuckets) {
        int recomputed = 0;
        int skippedNoBar = 0;
        int processed = 0;
        long segmentStartMillis = System.currentTimeMillis();
        LocalDateTime current = segmentStart;
        while (current.isBefore(segmentEnd)) {
            processed++;
            List<TornStockMarketBar15mDO> currentBars = bar15mDao.selectByBarStartTime(
                    current, Stock15mBarBuildService.BUILD_VERSION);
            if (currentBars.isEmpty()) {
                skippedNoBar++;
            } else {
                List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(current);
                if (!features.isEmpty()) {
                    recomputed++;
                }
            }

            if (processed % PROGRESS_LOG_INTERVAL == 0) {
                log.info("历史回填修复-进度, runId={}, segment={}, processedBuckets={}, forcedBars={}, "
                                + "recomputedFeatures={}, skippedNoBar={}, elapsedMs={}, currentBucket={}",
                        backfillRunId, segmentNo, processed, forcedBarBuckets, recomputed, skippedNoBar,
                        System.currentTimeMillis() - segmentStartMillis, current);
            }
            current = current.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        }
        log.info("历史回填修复-段完成, runId={}, segment={}, processedBuckets={}, forcedBars={}, "
                        + "recomputedFeatures={}, skippedNoBar={}, elapsedMs={}",
                backfillRunId, segmentNo, processed, forcedBarBuckets, recomputed, skippedNoBar,
                System.currentTimeMillis() - segmentStartMillis);
        return new RecomputeSegmentStats(recomputed, skippedNoBar);
    }

    // ==================== 私有方法 ====================

    /**
     * 确定补算的起始桶时间
     * <p>
     * 优先使用最后一个COMPLETED轮次的下一桶;若无已完成轮次,则使用最早历史记录所在桶。
     *
     * @return 起始桶时间(已对齐), 无法确定时返回null
     */
    private LocalDateTime determineRebuildStartTime() {
        TornStockMarketRoundDO lastCompleted = roundDao.selectLastCompleted();
        if (lastCompleted != null && lastCompleted.getRoundTime() != null) {
            LocalDateTime nextBucket = Stock15mBarBuildService.alignToBucket(lastCompleted.getRoundTime())
                    .plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
            log.info("最后已完成轮次时间={}, 下一桶={}", lastCompleted.getRoundTime(), nextBucket);
            return nextBucket;
        }

        log.info("不存在已完成轮次, 从历史最早记录开始补算");
        LocalDateTime earliest = findEarliestHistoryTime();
        if (earliest == null) {
            return null;
        }
        return Stock15mBarBuildService.alignToBucket(earliest);
    }

    /**
     * 按完整数据义务判定单个桶的修复动作,判定阶段只读,不加FOR UPDATE。
     * <p>
     * 判定顺序:FAILED_FINAL终态优先跳过;bar缺失或版本不一致走全量重建;
     * bar完整但feature缺失/不对应走特征补齐;bar+feature完整但round缺失走补建round;
     * round为可重试/构建中/版本不一致状态走恢复round;bar+feature+round+版本完整则完整跳过。
     *
     * @param bucketStartTime 桶开始时间(已对齐)
     * @return 单桶修复动作及已加载数据
     */
    private BucketRepairResult evaluateBucket(LocalDateTime bucketStartTime) {
        TornStockMarketRoundDO round = roundDao.selectByRoundTime(bucketStartTime);
        if (round != null && StockRoundStatusEnum.FAILED_FINAL.getCode().equals(round.getRoundStatus())) {
            return new BucketRepairResult(BucketRepairAction.SKIP_FAILED_FINAL, List.of(), List.of(), round);
        }

        List<TornStockMarketBar15mDO> bars = bar15mDao.selectByBarStartTime(
                bucketStartTime, Stock15mBarBuildService.BUILD_VERSION);
        if (CollectionUtils.isEmpty(bars)) {
            return new BucketRepairResult(BucketRepairAction.REBUILD_DATA, bars, List.of(), round);
        }

        List<TornStockStrategyFeature15mDO> features = feature15mDao.selectByBarStartTime(
                bucketStartTime, Stock15mFeatureBuildService.FEATURE_VERSION);
        if (!isFeatureComplete(bars, features)) {
            return new BucketRepairResult(BucketRepairAction.REPAIR_FEATURE, bars, features, round);
        }

        if (round == null) {
            return new BucketRepairResult(BucketRepairAction.REPAIR_ROUND, bars, features, null);
        }
        if (isRecoverableRound(round)) {
            return new BucketRepairResult(BucketRepairAction.RETRY_ROUND, bars, features, round);
        }
        return new BucketRepairResult(BucketRepairAction.COMPLETE, bars, features, round);
    }

    /**
     * 判断feature是否与bar按stocksId一一对应完整存在。
     * <p>
     * 同一桶内bar与feature的barStartTime相同,因此以stocksId集合相等表达一一对应。
     *
     * @param bars     当前版本bar列表
     * @param features 当前版本feature列表
     * @return 一一对应完整时返回true
     */
    private boolean isFeatureComplete(List<TornStockMarketBar15mDO> bars,
                                      List<TornStockStrategyFeature15mDO> features) {
        Set<Integer> usableBarStocks = bars.stream()
                .filter(Stock15mBarBuildService::isUsable)
                .map(TornStockMarketBar15mDO::getStocksId)
                .collect(Collectors.toSet());
        Set<Integer> featureStocks = features.stream()
                .map(TornStockStrategyFeature15mDO::getStocksId)
                .collect(Collectors.toSet());
        return usableBarStocks.equals(featureStocks);
    }

    /**
     * 判断轮次是否处于需要恢复至READY的状态。
     * <p>
     * {@code READY}/{@code COMPLETED}且版本一致时不可恢复(完整);其余状态
     * (PENDING/BUILDING_BAR/BUILDING_FEATURE/WAITING_DATA/FAILED_RETRYABLE/未知)或
     * 版本不一致均视为可恢复,需按缺口恢复。
     *
     * @param round 轮次记录
     * @return 需要恢复时返回true
     */
    private boolean isRecoverableRound(TornStockMarketRoundDO round) {
        if (round.getRoundStatus() == null) {
            return true;
        }
        if (StockRoundStatusEnum.READY.getCode().equals(round.getRoundStatus())
                || StockRoundStatusEnum.COMPLETED.getCode().equals(round.getRoundStatus())) {
            return !isRoundVersionCurrent(round);
        }
        return true;
    }

    /**
     * 判断轮次中的bar/feature版本是否与当前构建版本一致。
     *
     * @param round 轮次记录
     * @return 版本一致时返回true
     */
    private boolean isRoundVersionCurrent(TornStockMarketRoundDO round) {
        return Stock15mBarBuildService.BUILD_VERSION.equals(round.getBarBuildVersion())
                && Stock15mFeatureBuildService.FEATURE_VERSION.equals(round.getFeatureVersion());
    }

    /**
     * 全量重建单个桶: 构建bar -> 构建特征 -> 更新轮次为READY(纯数据构建不执行组合事务)。
     * <p>
     * 用于bar缺失或版本不一致的桶。重建时固定写入当前bar/feature版本与预期/可用股票数。
     *
     * @param bucketStartTime 桶开始时间(已对齐)
     */
    private void rebuildSingleBucket(LocalDateTime bucketStartTime) {
        LocalDateTime now = marketClock.now();
        TornStockMarketRoundDO round = createRound(bucketStartTime, now);
        String versionSnapshot = "barBuildVersion=" + round.getBarBuildVersion()
                + ", featureVersion=" + round.getFeatureVersion()
                + ", buyRuleVersion=" + round.getBuyRuleVersion()
                + ", sellRuleVersion=" + round.getSellRuleVersion()
                + ", allocationRuleVersion=" + round.getAllocationRuleVersion()
                + ", messageRuleVersion=" + round.getMessageRuleVersion();
        try {
            round.setRoundStatus(StockRoundStatusEnum.BUILDING_BAR.getCode());
            round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
            round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
            roundDao.updateById(round);

            List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(bucketStartTime);
            if (CollectionUtils.isEmpty(bars)) {
                log.warn("桶{}构建bar为空, 仍继续构建特征", bucketStartTime);
            }

            round.setRoundStatus(StockRoundStatusEnum.BUILDING_FEATURE.getCode());
            roundDao.updateById(round);

            List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(bucketStartTime);
            markRoundReady(round, bars.size(), features.size(), now);
            log.debug("桶{}数据构建完成(READY), 版本快照={}", bucketStartTime, versionSnapshot);
        } catch (Exception e) {
            log.error("桶{}重建失败, 阶段=BUILDING, 版本快照={}: {}", bucketStartTime, versionSnapshot,
                    e.getMessage(), e);
            markFailed(round, e);
            throw e;
        }
    }

    /**
     * 补齐单桶缺失的feature并恢复轮次为READY。
     * <p>
     * 适用于bar完整但feature缺失或与bar不对应的桶:不重建bar,只构建feature,
     * 创建或恢复round并最终置READY。
     *
     * @param bucketStartTime 桶开始时间(已对齐)
     * @param bars            已加载的当前版本bar列表
     * @param round           已存在的轮次(可能为null)
     */
    private void repairFeatureBucket(LocalDateTime bucketStartTime,
                                     List<TornStockMarketBar15mDO> bars,
                                     TornStockMarketRoundDO round) {
        LocalDateTime now = marketClock.now();
        TornStockMarketRoundDO target = round != null ? round : createRound(bucketStartTime, now);
        try {
            target.setRoundStatus(StockRoundStatusEnum.BUILDING_FEATURE.getCode());
            target.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
            target.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
            roundDao.updateById(target);

            List<TornStockStrategyFeature15mDO> features = featureBuildService.buildFeatures(bucketStartTime);
            markRoundReady(target, bars.size(), features.size(), now);
            log.info("桶{}feature补齐完成并置READY", bucketStartTime);
        } catch (Exception e) {
            log.error("桶{}feature补齐失败, 阶段=FEATURE: {}", bucketStartTime, e.getMessage(), e);
            markFailed(target, e);
            throw e;
        }
    }

    /**
     * 为bar+feature完整的桶创建或恢复round至READY。
     * <p>
     * 适用于round缺失(REPAIR_ROUND)或round处于可重试/构建中/版本不一致状态(RETRY_ROUND)的桶:
     * 不重建bar/feature,幂等创建或复用round并最终置READY。
     *
     * @param bucketStartTime 桶开始时间(已对齐)
     * @param bars            已加载的当前版本bar列表
     * @param features        已加载的当前版本feature列表
     * @param round           已存在的轮次(round缺失时可能为null)
     */
    private void restoreRoundReady(LocalDateTime bucketStartTime,
                                   List<TornStockMarketBar15mDO> bars,
                                   List<TornStockStrategyFeature15mDO> features,
                                   TornStockMarketRoundDO round) {
        LocalDateTime now = marketClock.now();
        TornStockMarketRoundDO target = round != null ? round : createRound(bucketStartTime, now);
        try {
            markRoundReady(target, bars.size(), features.size(), now);
            log.info("桶{}轮次恢复为READY, roundId={}", bucketStartTime, target.getId());
        } catch (Exception e) {
            log.error("桶{}轮次恢复失败, 阶段=READY: {}", bucketStartTime, e.getMessage(), e);
            markFailed(target, e);
            throw e;
        }
    }

    /**
     * 将轮次标记为READY并写入当前版本与计数(纯数据构建的终态)。
     *
     * @param round              轮次记录(须已持久化含主键)
     * @param expectedStockCount 预期股票数(bar数)
     * @param usableStockCount   可用股票数(feature数)
     * @param now                本次修复的审计时间(统一由时钟注入)
     */
    private void markRoundReady(TornStockMarketRoundDO round, int expectedStockCount,
                                int usableStockCount, LocalDateTime now) {
        round.setRoundStatus(StockRoundStatusEnum.READY.getCode());
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setExpectedStockCount(expectedStockCount);
        round.setUsableStockCount(usableStockCount);
        if (round.getStartedAt() == null) {
            round.setStartedAt(now);
        }
        round.setCompletedAt(now);
        roundDao.updateById(round);
    }

    /**
     * 为指定桶幂等创建或复用轮次记录(初始状态PENDING)。
     * <p>
     * 使用 {@link StockMarketRoundFactory} 统一填充四个规则版本与全部NOT NULL字段;
     * 使用数据库部分唯一索引 + {@code ON CONFLICT DO NOTHING} 幂等插入,首次插入后
     * 重新查询获取自增主键,已存在同桶有效轮次时复用现有记录,避免并发/重复重建产生
     * 重复round或唯一异常泄漏。判定与回查均使用普通读取,不加FOR UPDATE。
     *
     * @param bucketStartTime 桶开始时间
     * @param now             本次重建的审计时间(统一由时钟注入,不取墙钟)
     * @return 已持久化的轮次记录(含主键)
     */
    private TornStockMarketRoundDO createRound(LocalDateTime bucketStartTime, LocalDateTime now) {
        TornStockMarketRoundDO round = roundFactory.createRound(
                bucketStartTime, StockRoundStatusEnum.PENDING.getCode());
        round.setStartedAt(now);
        int inserted = roundDao.insertPendingRoundIgnoreConflict(round);
        if (inserted > 0) {
            TornStockMarketRoundDO persisted = roundDao.selectByRoundTime(bucketStartTime);
            if (persisted == null) {
                throw new IllegalStateException("历史重建-轮次插入后无法查询到持久化记录: " + bucketStartTime);
            }
            return persisted;
        }
        TornStockMarketRoundDO existing = roundDao.selectByRoundTime(bucketStartTime);
        if (existing == null) {
            throw new IllegalStateException("历史重建-同桶轮次已存在但无法查询: " + bucketStartTime);
        }
        log.info("历史重建-同桶轮次已存在,复用现有轮次: roundTime={}, id={}, status={}",
                bucketStartTime, existing.getId(), existing.getRoundStatus());
        return existing;
    }

    /**
     * 将轮次标记为可重试失败并记录错误信息。
     *
     * @param round 待标记的轮次记录
     * @param e     触发失败的异常
     */
    private void markFailed(TornStockMarketRoundDO round, Exception e) {
        round.setRoundStatus(StockRoundStatusEnum.FAILED_RETRYABLE.getCode());
        round.setErrorMessage(e.getMessage());
        roundDao.updateById(round);
    }

    /**
     * 计算桶数量
     *
     * @param start 起始桶(含)
     * @param end   结束桶(不含)
     * @return 桶总数
     */
    private long calculateBucketCount(LocalDateTime start, LocalDateTime end) {
        long minutesDiff = java.time.Duration.between(start, end).toMinutes();
        return minutesDiff / Stock15mBarBuildService.BUCKET_MINUTES;
    }

    /**
     * 单桶修复动作。
     */
    private enum BucketRepairAction {
        /**
         * bar/feature/round完整且版本一致,完整跳过。
         */
        COMPLETE,
        /**
         * bar完整但feature缺失或与bar不对应,构建feature并恢复round至READY。
         */
        REPAIR_FEATURE,
        /**
         * bar+feature完整但round缺失,幂等创建round并置READY。
         */
        REPAIR_ROUND,
        /**
         * bar+feature完整但round为可重试/构建中/版本不一致状态,恢复round至READY。
         */
        RETRY_ROUND,
        /**
         * bar缺失或版本不一致,全量重建bar/feature并置READY。
         */
        REBUILD_DATA,
        /**
         * round为FAILED_FINAL终态,记录原因后跳过,不自动重开。
         */
        SKIP_FAILED_FINAL
    }

    /**
     * 单桶修复判定结果(仅用于本服务私有编排,不落库)。
     *
     * @param action   修复动作
     * @param bars     当前版本bar列表(判定阶段已加载)
     * @param features 当前版本feature列表(判定阶段已加载)
     * @param round    轮次记录(可能为null)
     */
    private record BucketRepairResult(
            BucketRepairAction action,
            List<TornStockMarketBar15mDO> bars,
            List<TornStockStrategyFeature15mDO> features,
            TornStockMarketRoundDO round) {
    }

    /**
     * 回填驱动派生数据修复统计结果。
     *
     * @param forcedBarBuckets         受影响桶中强制重建出 bar 的桶数
     * @param dataOnlyRoundCount       写入数据修复终态 REPAIRED_DATA_ONLY 的轮次数
     *                                 (COMPLETED/FAILED_FINAL 保持终态不计入)
     * @param recomputedFeatureBuckets 后向重算范围内实际重算出 feature 的桶数
     * @param skippedNoBarBuckets      后向重算范围内无 bar 而跳过的桶数
     */
    public record BackfillRepairResult(
            int forcedBarBuckets,
            int dataOnlyRoundCount,
            int recomputedFeatureBuckets,
            int skippedNoBarBuckets) {

        /**
         * 实际产生数据写入的桶数(强制重建 bar 桶 + 重算 feature 桶)。
         *
         * @return 重建桶总数
         */
        public int rebuiltBucketCount() {
            return forcedBarBuckets + recomputedFeatureBuckets;
        }
    }

    /**
     * 单段 feature 重算统计。
     *
     * @param recomputed   段内重算出 feature 的桶数
     * @param skippedNoBar 段内无 bar 而跳过的桶数
     */
    private record RecomputeSegmentStats(int recomputed, int skippedNoBar) {
    }
}
