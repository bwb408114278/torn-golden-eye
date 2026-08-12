package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 动态SELL研究输入采集器。
 *
 * <p>公式冻结前仅采集研究输入(批次净收益/MFE/MAE/峰值回撤/特征与持有时间),统计覆盖率、
 * 缺失率与路径分布(按买入策略族);建议/交易/关闭恒为0,不实现任何动态SELL投资判断。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
final class StockReplayDynamicResearch {

    /**
     * 动态SELL研究决定(公式冻结前固定)。
     */
    static final String DYNAMIC_SHADOW_DECISION = "NOT_EVALUATED";
    /**
     * 动态SELL未评估原因(公式冻结前固定)。
     */
    static final String DYNAMIC_SHADOW_REASON = "DYNAMIC_RULE_NOT_FROZEN";
    /**
     * 统计精度。
     */
    private static final int STAT_SCALE = 10;

    private long observations;
    private long present;
    private long missing;
    private final TreeMap<String, Integer> pathByFamily = new TreeMap<>();

    /**
     * 采集一轮全部正式开放批次的研究输入。
     *
     * @param activeBatches  活跃批次
     * @param barByStock     本轮bar(按股票ID索引)
     * @param featureByStock 本轮特征(按股票ID索引)
     * @param t              本轮时间
     */
    void collect(List<TornStockVirtualBatchDO> activeBatches,
                 Map<Integer, TornStockMarketBar15mDO> barByStock,
                 Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                 LocalDateTime t) {
        for (TornStockVirtualBatchDO batch : activeBatches) {
            if (!isFormalOpen(batch)) {
                continue;
            }
            collectOpen(batch, barByStock.get(batch.getStocksId()),
                    featureByStock.get(batch.getStocksId()), t);
        }
    }

    /**
     * 动态SELL研究摘要。
     *
     * @return 动态SELL研究摘要
     */
    StockReplaySummary.DynamicSellSummary summary() {
        long total = present + missing;
        BigDecimal coverage = total == 0 ? null
                : BigDecimal.valueOf(present)
                .divide(BigDecimal.valueOf(total), STAT_SCALE, RoundingMode.HALF_UP);
        BigDecimal missingRate = total == 0 ? null
                : BigDecimal.valueOf(missing)
                .divide(BigDecimal.valueOf(total), STAT_SCALE, RoundingMode.HALF_UP);
        return new StockReplaySummary.DynamicSellSummary(
                DYNAMIC_SHADOW_DECISION, DYNAMIC_SHADOW_REASON, observations,
                coverage, missingRate, new TreeMap<>(pathByFamily), 0, 0, 0);
    }

    private void collectOpen(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO bar,
                             TornStockStrategyFeature15mDO feature, LocalDateTime t) {
        if (!Stock15mBarBuildService.isUsable(bar)) {
            return;
        }
        observations++;
        accumulate(researchInputs(batch, feature, holdHours(batch, t)));
        pathByFamily.merge(
                batch.getPrimaryStrategy() == null ? "UNKNOWN" : batch.getPrimaryStrategy(),
                1, Integer::sum);
    }

    private void accumulate(List<BigDecimal> inputs) {
        for (BigDecimal value : inputs) {
            if (value != null) {
                present++;
            } else {
                missing++;
            }
        }
    }

    private static boolean isFormalOpen(TornStockVirtualBatchDO batch) {
        return StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType())
                && StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus());
    }

    private static BigDecimal holdHours(TornStockVirtualBatchDO batch, LocalDateTime t) {
        return batch.getEntryTime() == null ? null
                : BigDecimal.valueOf(Duration.between(batch.getEntryTime(), t).toMinutes() / 60.0);
    }

    private static List<BigDecimal> researchInputs(TornStockVirtualBatchDO batch,
                                                   TornStockStrategyFeature15mDO feature,
                                                   BigDecimal holdHours) {
        return java.util.Arrays.asList(
                batch.getCurrentNetReturn(),
                batch.getMfe(),
                batch.getMae(),
                batch.getPeakDrawdown(),
                value(feature, TornStockStrategyFeature15mDO::getZscore1d),
                value(feature, TornStockStrategyFeature15mDO::getReturn6h),
                value(feature, TornStockStrategyFeature15mDO::getReturn1d),
                value(feature, TornStockStrategyFeature15mDO::getMa7d),
                value(feature, TornStockStrategyFeature15mDO::getMa30d),
                value(feature, TornStockStrategyFeature15mDO::getPosition30),
                value(feature, TornStockStrategyFeature15mDO::getWidth30d),
                holdHours);
    }

    private static BigDecimal value(TornStockStrategyFeature15mDO feature,
                                    Function<TornStockStrategyFeature15mDO, BigDecimal> getter) {
        return feature == null ? null : getter.apply(feature);
    }
}
