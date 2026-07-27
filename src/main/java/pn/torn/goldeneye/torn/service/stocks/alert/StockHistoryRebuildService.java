package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票历史重建服务 - 从现有torn_stocks_history按15分钟规则重建历史bar和特征
 * <p>
 * 用于冷启动补偿与历史回放:按15分钟桶逐桶重建bar与策略特征,
 * 并维护 {@link TornStockMarketRoundDO} 轮次状态(BUILDING_BAR -> BUILDING_FEATURE -> COMPLETED),
 * 作为策略回放与审计的主线索。复用 {@link Stock15mBarBuildService} 与
 * {@link Stock15mFeatureBuildService} 的生产构建逻辑,不维护两套逻辑。
 *
 * <h3>重建流程</h3>
 * <ol>
 *   <li>对齐起止时间到15分钟桶边界</li>
 *   <li>逐桶检查是否已存在bar(幂等),已存在则跳过</li>
 *   <li>调用bar构建服务聚合分钟采样,创建/更新轮次为BUILDING_BAR</li>
 *   <li>调用特征构建服务计算策略特征,轮次转为BUILDING_FEATURE</li>
 *   <li>轮次标记COMPLETED,每10个桶输出进度日志</li>
 * </ol>
 *
 * @author Bai
 * @version 1.2.12
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

    private final Stock15mBarBuildService barBuildService;
    private final Stock15mFeatureBuildService featureBuildService;
    private final TornStockMarketRoundDAO roundDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final TornStockMarketBar15mDAO bar15mDao;

    // ==================== 公开入口 ====================

    /**
     * 从指定起始时间到结束时间,按15分钟桶逐桶重建历史bar与特征
     * <p>
     * 自动将startTime与endTime对齐到15分钟桶边界。对每个桶依次构建bar、构建特征、
     * 创建/更新轮次记录(BUILDING_BAR -> BUILDING_FEATURE -> COMPLETED)。已存在bar的桶会被跳过以保证幂等。
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
            if (bucketAlreadyBuilt(current)) {
                log.debug("桶{}已存在bar, 跳过重建", current);
                processedCount++;
                current = current.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
                continue;
            }

            rebuildSingleBucket(current);
            rebuiltCount++;
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
     * 重建单个桶: 构建bar -> 构建特征 -> 更新轮次为READY(纯数据构建不执行组合事务)
     *
     * @param bucketStartTime 桶开始时间(已对齐)
     */
    private void rebuildSingleBucket(LocalDateTime bucketStartTime) {
        TornStockMarketRoundDO round = createRound(bucketStartTime);
        try {
            round.setRoundStatus(StockRoundStatusEnum.BUILDING_BAR.getCode());
            roundDao.updateById(round);

            List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(bucketStartTime);
            if (CollectionUtils.isEmpty(bars)) {
                log.warn("桶{}构建bar为空, 仍继续构建特征", bucketStartTime);
            }

            round.setRoundStatus(StockRoundStatusEnum.BUILDING_FEATURE.getCode());
            roundDao.updateById(round);

            featureBuildService.buildFeatures(bucketStartTime);

            round.setRoundStatus(StockRoundStatusEnum.READY.getCode());
            round.setCompletedAt(LocalDateTime.now());
            roundDao.updateById(round);
            log.debug("桶{}数据构建完成(READY)", bucketStartTime);
        } catch (Exception e) {
            log.error("桶{}重建失败: {}", bucketStartTime, e.getMessage(), e);
            round.setRoundStatus(StockRoundStatusEnum.FAILED_RETRYABLE.getCode());
            round.setErrorMessage(e.getMessage());
            roundDao.updateById(round);
            throw e;
        }
    }

    /**
     * 为指定桶创建轮次记录(初始状态PENDING)
     *
     * @param bucketStartTime 桶开始时间
     * @return 已保存的轮次记录
     */
    private TornStockMarketRoundDO createRound(LocalDateTime bucketStartTime) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setRoundTime(bucketStartTime);
        round.setRoundStatus(StockRoundStatusEnum.PENDING.getCode());
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setAttemptCount(0);
        round.setStartedAt(LocalDateTime.now());
        roundDao.save(round);
        return round;
    }

    /**
     * 检查指定桶是否已存在bar(幂等判断)
     *
     * @param bucketStartTime 桶开始时间
     * @return true表示已存在bar
     */
    private boolean bucketAlreadyBuilt(LocalDateTime bucketStartTime) {
        List<TornStockMarketBar15mDO> existing = bar15mDao.selectByBarStartTime(bucketStartTime, Stock15mBarBuildService.BUILD_VERSION);
        return !CollectionUtils.isEmpty(existing);
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
}
