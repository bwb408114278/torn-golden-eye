package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 股票历史回放执行器 - 复用生产代码链按15分钟桶逐桶回放,执行全量策略验证
 * <p>
 * 回放是冷启动补偿之外的独立验证手段:在历史已构建的bar与特征基础上,
 * 逐桶加载轮次快照并执行 {@link StockRoundTransactionService#executeRound} ,
 * 让5槽正式组合、无限资金影子、拒绝观察、动态卖出影子、高风险观察与
 * 当前Java原始买入对照共用同一套bar质量、特征、买入、排序、成交、资金和退出实现,
 * 禁止维护两套逻辑。
 *
 * <h3>回放流程</h3>
 * <ol>
 *   <li>对齐起止时间到15分钟桶边界</li>
 *   <li>逐桶调用 {@link StockHistoryRebuildService#rebuildHistory} 确保bar与特征已构建(已存在则跳过,幂等)</li>
 *   <li>调用 {@link StockMarketRoundLoader#loadRoundSnapshot} 批量加载本轮决策快照</li>
 *   <li>调用 {@link StockRoundTransactionService#executeRound} 在事务内执行轮次(数据库写入由其完成)</li>
 *   <li>通过查询本轮写入的信号事件与成交批次统计买入/卖出信号数</li>
 *   <li>每 {@value #PROGRESS_LOG_INTERVAL} 个桶输出一次进度日志</li>
 * </ol>
 *
 * <h3>与生产调度的关系</h3>
 * <ul>
 *   <li>本执行器不写数据库,所有持久化由 {@link StockRoundTransactionService} 在事务内完成</li>
 *   <li>回放与 {@link VipStockAlertScheduler} 共用同一代码链,不另建逻辑</li>
 *   <li>已COMPLETED的轮次由 {@link StockRoundTransactionService} 内部幂等保护,重复执行会抛出
 *       {@link IllegalStateException} ,本执行器捕获并计入跳过桶数</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReplayExecutor {

    /**
     * 进度日志输出间隔(每处理多少个桶输出一次info日志)
     */
    private static final int PROGRESS_LOG_INTERVAL = 10;

    private final StockHistoryRebuildService historyRebuildService;
    private final StockMarketRoundLoader marketRoundLoader;
    private final StockRoundTransactionService roundTransactionService;
    private final StockPortfolioInitService portfolioInitService;
    private final StockMonthlyStateInitService monthlyStateInitService;
    private final TornStockSignalEventDAO signalEventDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;

    // ==================== 公开入口 ====================

    /**
     * 从指定起始时间到结束时间,按15分钟桶逐桶回放策略轮次。
     * <p>
     * 自动将startTime与endTime对齐到15分钟桶边界。对每个桶依次:
     * <ol>
     *   <li>调用 {@link StockHistoryRebuildService#rebuildHistory} 确保bar与特征已构建(幂等,已存在则跳过)</li>
     *   <li>调用 {@link StockMarketRoundLoader#loadRoundSnapshot} 加载本轮快照</li>
     *   <li>调用 {@link StockRoundTransactionService#executeRound} 执行轮次事务</li>
     *   <li>统计本轮买入信号数(查询信号事件)与卖出信号数(查询成交平仓批次)</li>
     * </ol>
     * 回放过程可能非常耗时,每 {@value #PROGRESS_LOG_INTERVAL} 个桶输出一次进度日志。
     * 本方法本身不写数据库,数据库写入由 {@link StockRoundTransactionService} 在事务内完成。
     *
     * @param startTime 回放起始时间(方法内部会自动对齐到桶边界,含)
     * @param endTime   回放结束时间(应为已结束桶的边界,方法内部会自动对齐,不含)
     * @return 回放统计(回放桶数、买入信号数、卖出信号数等)
     */
    public ReplayStats executeReplay(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime start = Stock15mBarBuildService.alignToBucket(startTime);
        LocalDateTime end = Stock15mBarBuildService.alignToBucket(endTime);

        if (!start.isBefore(end)) {
            log.warn("回放区间无效, start={}, end={}, 起始时间需早于结束时间", start, end);
            return new ReplayStats(0, 0, 0, 0, 0, start, end);
        }

        long totalBuckets = calculateBucketCount(start, end);
        log.info("开始历史回放, start={}, end={}, 预计{}个桶", start, end, totalBuckets);

        int processedBuckets = 0;
        int skippedBuckets = 0;
        int buySignals = 0;
        int sellSignals = 0;

        LocalDateTime current = start;
        while (current.isBefore(end)) {
            try {
                processSingleBucket(current);
                processedBuckets++;

                // 统计本轮买入/卖出信号
                buySignals += countBuySignals(current);
                sellSignals += countSellSignals(current);
            } catch (IllegalStateException e) {
                // 轮次已完成,幂等跳过
                log.debug("桶{}已完成轮次,跳过回放", current);
                skippedBuckets++;
            } catch (Exception e) {
                log.error("桶{}回放失败: {}", current, e.getMessage(), e);
                skippedBuckets++;
            }

            if ((processedBuckets + skippedBuckets) % PROGRESS_LOG_INTERVAL == 0) {
                log.info("回放进度: 已处理{}/{}个桶, 跳过{}个, 买入信号={}, 卖出信号={}, 当前桶={}",
                        processedBuckets + skippedBuckets, totalBuckets,
                        skippedBuckets, buySignals, sellSignals, current);
            }

            current = current.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        }

        ReplayStats stats = new ReplayStats(
                (int) totalBuckets, processedBuckets, skippedBuckets,
                buySignals, sellSignals, start, end);
        log.info("历史回放完成, start={}, end={}, 预计{}个桶, 实际处理{}个, 跳过{}个, 买入信号={}, 卖出信号={}",
                start, end, totalBuckets, processedBuckets, skippedBuckets, buySignals, sellSignals);
        return stats;
    }

    /**
     * 从torn_stocks_history最早记录开始回放到当前已结束桶。
     * <p>
     * 执行顺序:
     * <ol>
     *   <li>调用 {@link StockPortfolioInitService#verifyAndInitSlots()} 确保5槽正式组合已初始化</li>
     *   <li>调用 {@link StockMonthlyStateInitService#initCurrentMonth()} 确保当月月度状态草稿已生成</li>
     *   <li>调用 {@link StockHistoryRebuildService#findEarliestHistoryTime()} 确定回放起点</li>
     *   <li>计算当前已结束桶边界作为回放终点</li>
     *   <li>调用 {@link #executeReplay(LocalDateTime, LocalDateTime)} 执行逐桶回放</li>
     * </ol>
     * 前置初始化步骤独立try-catch,单步失败仅记录日志不阻塞回放(组合/月度状态可能已由启动补偿初始化)。
     *
     * @return 回放统计;无法确定起点时返回空统计
     */
    public ReplayStats executeFullReplay() {
        log.info("全量回放开始,执行前置初始化");

        try {
            portfolioInitService.verifyAndInitSlots();
        } catch (Exception e) {
            log.error("全量回放-组合槽位验证失败,继续后续步骤", e);
        }

        try {
            monthlyStateInitService.initCurrentMonth();
        } catch (Exception e) {
            log.error("全量回放-月度状态初始化失败,继续后续步骤", e);
        }

        LocalDateTime startTime = historyRebuildService.findEarliestHistoryTime();
        if (startTime == null) {
            log.warn("全量回放-无法确定最早历史记录时间,退出回放");
            return new ReplayStats(0, 0, 0, 0, 0, null, null);
        }

        LocalDateTime alignedStart = Stock15mBarBuildService.alignToBucket(startTime);
        LocalDateTime endTime = Stock15mBarBuildService.alignToBucket(LocalDateTime.now())
                .minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);

        log.info("全量回放-前置初始化完成, start={}, end={}", alignedStart, endTime);
        return executeReplay(alignedStart, endTime);
    }

    // ==================== 私有方法 ====================

    /**
     * 处理单个回放桶: 确保bar/特征已构建 -> 加载快照 -> 执行轮次事务
     * <p>
     * 先调用 {@link StockHistoryRebuildService#rebuildHistory} 以单桶区间触发幂等构建
     * (已存在bar的桶会被自动跳过),随后加载快照并执行轮次事务。
     *
     * @param bucketTime 桶开始时间(已对齐)
     */
    private void processSingleBucket(LocalDateTime bucketTime) {
        // 确保bar与特征已构建(幂等,已存在则跳过)
        LocalDateTime nextBucket = bucketTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        historyRebuildService.rebuildHistory(bucketTime, nextBucket);

        // 加载本轮快照
        RoundSnapshot snapshot = marketRoundLoader.loadRoundSnapshot(bucketTime);

        // 执行轮次事务(数据库写入由StockRoundTransactionService在事务内完成)
        roundTransactionService.executeRound(bucketTime, snapshot);
    }

    /**
     * 统计指定轮次的买入信号数。
     * <p>
     * 查询信号事件表中roundTime等于该桶时间的全部记录,每条记录代表一次
     * 边沿触发的买入信号(含正式、影子、拒绝观察)。
     *
     * @param roundTime 轮次时间
     * @return 本轮买入信号事件数量
     */
    private int countBuySignals(LocalDateTime roundTime) {
        var events = signalEventDao.selectByRoundTime(roundTime);
        return events == null ? 0 : events.size();
    }

    /**
     * 统计指定轮次的卖出信号数。
     * <p>
     * 查询虚拟批次表中exitTime等于该桶时间的全部记录,每条记录代表一次
     * 已成交的卖出(平仓)信号。
     *
     * @param roundTime 轮次时间
     * @return 本轮卖出成交批次数量
     */
    private int countSellSignals(LocalDateTime roundTime) {
        var batches = virtualBatchDao.lambdaQuery()
                .eq(TornStockVirtualBatchDO::getExitTime, roundTime)
                .list();
        if (CollectionUtils.isEmpty(batches)) {
            return 0;
        }
        return batches.size();
    }

    /**
     * 计算桶数量
     *
     * @param start 起始桶(含)
     * @param end   结束桶(不含)
     * @return 桶总数
     */
    private long calculateBucketCount(LocalDateTime start, LocalDateTime end) {
        long minutesDiff = Duration.between(start, end).toMinutes();
        return minutesDiff / Stock15mBarBuildService.BUCKET_MINUTES;
    }

    // ==================== 值对象 ====================

    /**
     * 回放统计值对象 - 封装单次回放执行的统计结果
     * <p>
     * 不可变record,由 {@link #executeReplay(LocalDateTime, LocalDateTime)} 构造,
     * 供调用方获取回放覆盖范围与信号触发情况。
     *
     * @param totalBuckets     预计桶总数
     * @param processedBuckets 实际处理(非跳过)的桶数量
     * @param skippedBuckets   跳过的桶数量(含已完成幂等跳过与异常跳过)
     * @param buySignals       回放期间触发的买入信号总数
     * @param sellSignals      回放期间触发的卖出信号总数
     * @param startTime        回放起始时间(已对齐)
     * @param endTime          回放结束时间(已对齐)
     */
    public record ReplayStats(
            int totalBuckets,
            int processedBuckets,
            int skippedBuckets,
            int buySignals,
            int sellSignals,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }
}
