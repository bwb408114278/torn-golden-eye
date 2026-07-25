package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService.ExitEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 股票批次路径服务 - 更新开放批次持仓路径并评估退出条件
 * <p>
 * 步骤4: 用本轮bar价格更新OPEN批次的峰值/谷值/MFE/MAE/回撤,生成逐轮BatchMark。
 * 步骤5: 对每个OPEN批次调用退出评估,命中时置为EXIT_PENDING。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBatchPathService {

    /**
     * 正式决策-持有
     */
    private static final String FORMAL_DECISION_HOLD = "HOLD";
    /**
     * 正式决策原因-持仓跟踪中
     */
    private static final String FORMAL_REASON_HOLDING = "持仓跟踪中";
    /**
     * BigDecimal运算精度
     */
    private static final int MATH_SCALE = 18;

    private final StockBatchExitService batchExitService;

    /**
     * 更新所有OPEN批次的持仓路径并生成BatchMark。
     * <p>
     * 对每个OPEN批次,用本轮bar价格更新峰值/谷值,计算MFE/MAE/回撤和当前净收益,
     * 生成BatchMark记录本轮快照。
     *
     * @param snapshot   轮次快照
     * @param barByStock 按股票ID索引的bar映射
     * @param roundTime  本轮时间
     * @return 生成的BatchMark列表
     */
    public List<TornStockBatchMarkDO> updatePaths(RoundSnapshot snapshot,
                                                  Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                  LocalDateTime roundTime) {
        List<TornStockBatchMarkDO> marks = new ArrayList<>();
        List<TornStockVirtualBatchDO> openBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .toList();

        if (openBatches.isEmpty()) {
            log.debug("无开放批次需要更新路径");
            return marks;
        }

        for (TornStockVirtualBatchDO batch : openBatches) {
            TornStockBatchMarkDO mark = updateSingleBatchPath(batch, barByStock, roundTime);
            if (mark != null) {
                marks.add(mark);
            }
        }
        return marks;
    }

    /**
     * 对每个OPEN批次评估退出条件,命中则置为EXIT_PENDING。
     *
     * @param snapshot       轮次快照
     * @param barByStock     按股票ID索引的bar映射
     * @param featureByStock 按股票ID索引的特征映射
     * @param roundTime      本轮时间
     */
    public void evaluateExits(RoundSnapshot snapshot,
                              Map<Integer, TornStockMarketBar15mDO> barByStock,
                              Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                              LocalDateTime roundTime) {
        List<TornStockVirtualBatchDO> openBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .toList();

        for (TornStockVirtualBatchDO batch : openBatches) {
            evaluateSingleBatchExit(batch, barByStock, featureByStock, roundTime);
        }
    }

    /**
     * 更新单个批次的持仓路径。
     *
     * @param batch      开放批次
     * @param barByStock bar映射
     * @param roundTime  本轮时间
     * @return BatchMark;bar不可用时返回null
     */
    private TornStockBatchMarkDO updateSingleBatchPath(TornStockVirtualBatchDO batch,
                                                       Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                       LocalDateTime roundTime) {
        TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());
        if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
            log.debug("开放批次[{}]本轮bar不可用,跳过路径更新", batch.getBatchNo());
            return null;
        }

        BigDecimal currentPrice = currentBar.getLastPrice();
        BigDecimal entryPrice = batch.getEntryReferencePrice();
        if (entryPrice == null || entryPrice.signum() <= 0) {
            log.warn("开放批次[{}]入场参考价缺失,跳过路径更新", batch.getBatchNo());
            return null;
        }

        BigDecimal newPeak = resolvePeakPrice(batch, currentPrice, entryPrice);
        BigDecimal newTrough = resolveTroughPrice(batch, currentPrice, entryPrice);
        batch.setPeakPrice(newPeak);
        batch.setTroughPrice(newTrough);

        BigDecimal mfe = calculateMfe(entryPrice, newPeak);
        BigDecimal mae = calculateMae(entryPrice, newTrough);
        BigDecimal peakDrawdown = calculatePeakDrawdown(newPeak, newTrough);
        BigDecimal currentNetReturn = StockPortfolioService.calculateNetReturn(entryPrice, currentPrice);

        batch.setMfe(mfe);
        batch.setMae(mae);
        batch.setPeakDrawdown(peakDrawdown);
        batch.setCurrentNetReturn(currentNetReturn);

        BatchPathMetrics metrics = new BatchPathMetrics(
                currentPrice, currentNetReturn, newPeak, newTrough, mfe, mae, peakDrawdown);
        TornStockBatchMarkDO mark = buildBatchMark(batch, metrics, roundTime);

        log.debug("开放批次路径更新: batchNo={}, price={}, peak={}, trough={}, mfe={}, mae={}, netReturn={}",
                batch.getBatchNo(), currentPrice, newPeak, newTrough, mfe, mae, currentNetReturn);
        return mark;
    }

    /**
     * 评估单个批次的退出条件。
     *
     * @param batch          开放批次
     * @param barByStock     bar映射
     * @param featureByStock 特征映射
     * @param roundTime      本轮时间
     */
    private void evaluateSingleBatchExit(TornStockVirtualBatchDO batch,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock,
                                         Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                                         LocalDateTime roundTime) {
        TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());
        if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
            return;
        }

        TornStockStrategyFeature15mDO feature = featureByStock.get(batch.getStocksId());
        BigDecimal position30 = feature != null ? feature.getPosition30() : null;
        BigDecimal low30d = feature != null ? feature.getLow30d() : null;
        BigDecimal high30d = feature != null ? feature.getHigh30d() : null;

        ExitEvaluation evaluation = batchExitService.evaluateExit(
                batch, currentBar.getLastPrice(), position30, low30d, high30d);

        if (evaluation.shouldExit()) {
            batch.setBatchStatus(StockBatchStatusEnum.EXIT_PENDING.getCode());
            batch.setExitSignalTime(roundTime);
            batch.setExpectedExitBarTime(roundTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
            batch.setExitReason(evaluation.closeType() != null
                    ? evaluation.closeType().getCode() : null);
            log.info("开放批次触发退出: batchNo={}, stocksId={}, closeType={}, reason={}",
                    batch.getBatchNo(), batch.getStocksId(),
                    evaluation.closeType(), evaluation.reason());
        }
    }

    /**
     * 解析峰值价格: 取当前价与历史峰值的较大值。
     *
     * @param batch        批次
     * @param currentPrice 当前价格
     * @param entryPrice   入场价格
     * @return 新的峰值价格
     */
    private BigDecimal resolvePeakPrice(TornStockVirtualBatchDO batch,
                                        BigDecimal currentPrice, BigDecimal entryPrice) {
        BigDecimal currentPeak = batch.getPeakPrice() != null ? batch.getPeakPrice() : entryPrice;
        return currentPrice.compareTo(currentPeak) > 0 ? currentPrice : currentPeak;
    }

    /**
     * 解析谷值价格: 取当前价与历史谷值的较小值。
     *
     * @param batch        批次
     * @param currentPrice 当前价格
     * @param entryPrice   入场价格
     * @return 新的谷值价格
     */
    private BigDecimal resolveTroughPrice(TornStockVirtualBatchDO batch,
                                          BigDecimal currentPrice, BigDecimal entryPrice) {
        BigDecimal currentTrough = batch.getTroughPrice() != null ? batch.getTroughPrice() : entryPrice;
        return currentPrice.compareTo(currentTrough) < 0 ? currentPrice : currentTrough;
    }

    /**
     * 构建批次标记记录。
     *
     * @param batch     批次
     * @param metrics   路径指标
     * @param roundTime 本轮时间
     * @return 批次标记DO
     */
    private TornStockBatchMarkDO buildBatchMark(TornStockVirtualBatchDO batch,
                                                BatchPathMetrics metrics,
                                                LocalDateTime roundTime) {
        TornStockBatchMarkDO mark = new TornStockBatchMarkDO();
        mark.setBatchId(batch.getId());
        mark.setRoundTime(roundTime);
        mark.setReferencePrice(metrics.currentPrice());
        mark.setCurrentNetReturn(metrics.currentNetReturn());
        mark.setPeakPrice(metrics.peakPrice());
        mark.setTroughPrice(metrics.troughPrice());
        mark.setMfe(metrics.mfe());
        mark.setMae(metrics.mae());
        mark.setPeakDrawdown(metrics.peakDrawdown());
        mark.setFormalDecision(FORMAL_DECISION_HOLD);
        mark.setFormalReason(FORMAL_REASON_HOLDING);
        return mark;
    }

    /**
     * 计算MFE(最大有利偏移)。
     * <p>
     * mfe = (peakPrice - entryReferencePrice) / entryReferencePrice
     *
     * @param entryReferencePrice 入场参考价
     * @param peakPrice           峰值价格
     * @return MFE;入场价为非正数时返回0
     */
    private BigDecimal calculateMfe(BigDecimal entryReferencePrice, BigDecimal peakPrice) {
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0 || peakPrice == null) {
            return BigDecimal.ZERO;
        }
        return peakPrice
                .subtract(entryReferencePrice)
                .divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算MAE(最大不利偏移)。
     * <p>
     * mae = (troughPrice - entryReferencePrice) / entryReferencePrice
     *
     * @param entryReferencePrice 入场参考价
     * @param troughPrice         谷值价格
     * @return MAE;入场价为非正数时返回0
     */
    private BigDecimal calculateMae(BigDecimal entryReferencePrice, BigDecimal troughPrice) {
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0 || troughPrice == null) {
            return BigDecimal.ZERO;
        }
        return troughPrice
                .subtract(entryReferencePrice)
                .divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算峰值回撤。
     * <p>
     * peakDrawdown = (troughPrice - peakPrice) / peakPrice
     *
     * @param peakPrice   峰值价格
     * @param troughPrice 谷值价格
     * @return 峰值回撤;峰值为非正数时返回0
     */
    private BigDecimal calculatePeakDrawdown(BigDecimal peakPrice, BigDecimal troughPrice) {
        if (peakPrice == null || peakPrice.signum() <= 0 || troughPrice == null) {
            return BigDecimal.ZERO;
        }
        return troughPrice
                .subtract(peakPrice)
                .divide(peakPrice, MATH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 批次路径指标值对象
     *
     * @param currentPrice     本轮参考价
     * @param currentNetReturn 本轮净收益
     * @param peakPrice        峰值价格
     * @param troughPrice      谷值价格
     * @param mfe              最大有利偏移
     * @param mae              最大不利偏移
     * @param peakDrawdown     峰值回撤
     */
    private record BatchPathMetrics(
            BigDecimal currentPrice,
            BigDecimal currentNetReturn,
            BigDecimal peakPrice,
            BigDecimal troughPrice,
            BigDecimal mfe,
            BigDecimal mae,
            BigDecimal peakDrawdown
    ) {
    }
}
