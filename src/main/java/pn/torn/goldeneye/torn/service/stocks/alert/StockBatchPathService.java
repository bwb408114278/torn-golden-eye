package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService.ExitEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票批次路径服务 - 更新开放批次持仓路径并评估退出条件
 * <p>
 * 步骤4-5: 用本轮bar价格更新OPEN批次的峰值/谷值/MFE/MAE/回撤，评估退出后生成逐轮BatchMark。
 * 同时维护 DATA_STALE 状态机: OPEN批次bar不可用时转入DATA_STALE,
 * DATA_STALE批次bar恢复时回到OPEN,相应切换槽位的STALE/OCCUPIED状态。
 * 对每个OPEN批次调用退出评估，命中时置为EXIT_PENDING，并将实际决定与规则输入固化到BatchMark。
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
     * 正式决策-卖出
     */
    private static final String FORMAL_DECISION_SELL = "SELL";
    /**
     * BigDecimal运算精度
     */
    private static final int MATH_SCALE = 18;

    private final StockBatchExitService batchExitService;

    /**
     * 更新所有持仓中批次的持仓路径，评估退出条件后生成BatchMark。
     * <p>
     * 遍历 OPEN 与 DATA_STALE 状态批次,执行数据陈旧状态机:
     * <ul>
     *   <li>OPEN批次本轮bar不可用 -&gt; 状态置为 DATA_STALE, slotStatus置为 STALE,跳过路径更新</li>
     *   <li>DATA_STALE批次本轮bar恢复可用 -&gt; 状态恢复为 OPEN, slotStatus恢复 OCCUPIED,继续路径更新</li>
     *   <li>DATA_STALE批次本轮bar仍不可用 -&gt; 保持 DATA_STALE,跳过路径更新</li>
     * </ul>
     * 对OPEN(含刚恢复的)批次,用本轮bar价格更新峰值/谷值,计算MFE/MAE/回撤和当前净收益,
     * 对OPEN(含刚恢复的)批次，先用本轮bar价格更新峰值/谷值，随后评估退出条件，
     * 最后生成包含实际退出输入与正式决定的BatchMark记录。
     *
     * @param snapshot       轮次快照
     * @param barByStock     按股票ID索引的bar映射
     * @param featureByStock 按股票ID索引的退出特征映射
     * @param roundTime      本轮时间
     * @return 生成的BatchMark列表(DATA_STALE批次不产生BatchMark)
     */
    public List<TornStockBatchMarkDO> updatePathsAndEvaluateExits(
            RoundSnapshot snapshot,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
            LocalDateTime roundTime) {
        List<TornStockBatchMarkDO> marks = new ArrayList<>();
        List<TornStockVirtualBatchDO> activeBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.DATA_STALE.getCode().equals(batch.getBatchStatus()))
                .toList();

        if (activeBatches.isEmpty()) {
            log.debug("无开放或数据陈旧批次需要更新路径");
            return marks;
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = StockPortfolioService.indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : activeBatches) {
            BatchPathMetrics metrics = updateSingleBatchPath(batch, barByStock, slotById);
            if (metrics != null) {
                TornStockStrategyFeature15mDO feature = featureByStock.get(batch.getStocksId());
                ExitEvaluation evaluation = evaluateSingleBatchExit(batch, metrics.currentPrice(), feature, roundTime);
                marks.add(buildBatchMark(batch, metrics, feature, evaluation, roundTime));
            }
        }
        return marks;
    }

    /**
     * 更新单个批次的持仓路径,处理DATA_STALE状态机。
     * <p>
     * 状态机规则:
     * <ul>
     *   <li>OPEN批次本轮bar不可用 -&gt; 置为DATA_STALE, slotStatus置STALE, 返回null</li>
     *   <li>DATA_STALE批次本轮bar可用 -&gt; 恢复OPEN, slotStatus恢复OCCUPIED, 继续路径更新</li>
     *   <li>DATA_STALE批次本轮bar仍不可用 -&gt; 保持DATA_STALE, 返回null</li>
     * </ul>
     * bar可用且批次为OPEN(或刚恢复)时,用本轮bar价格更新峰值/谷值,计算MFE/MAE/回撤,
     * 退出评估完成后，由调用方据此生成BatchMark记录本轮快照。
     *
     * @param batch      开放或数据陈旧批次
     * @param barByStock bar映射
     * @param slotById   按槽位ID索引的映射(用于切换slotStatus)
     * @return 路径指标；DATA_STALE状态、bar不可用或入场价无效时返回null
     */
    private BatchPathMetrics updateSingleBatchPath(TornStockVirtualBatchDO batch,
                                                   Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                   Map<Long, TornStockPortfolioSlotDO> slotById) {
        TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());
        boolean barUsable = Stock15mBarBuildService.isUsable(currentBar);
        String currentStatus = batch.getBatchStatus();

        // DATA_STALE状态机: bar可用时恢复, bar不可用时保持
        if (StockBatchStatusEnum.DATA_STALE.getCode().equals(currentStatus)) {
            if (!barUsable) {
                log.debug("数据陈旧批次[{}]本轮bar仍不可用,保持DATA_STALE", batch.getBatchNo());
                return null;
            }
            recoverFromDataStale(batch, slotById);
        } else if (!barUsable) {
            // OPEN批次bar不可用 -> 转入DATA_STALE
            transitionToDataStale(batch, slotById);
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

        log.debug("开放批次路径更新: batchNo={}, price={}, peak={}, trough={}, mfe={}, mae={}, netReturn={}",
                batch.getBatchNo(), currentPrice, newPeak, newTrough, mfe, mae, currentNetReturn);
        return metrics;
    }

    /**
     * 评估单个批次的退出条件。
     *
     * @param batch        开放批次
     * @param currentPrice 本轮bar实际价格
     * @param feature      本轮退出特征，可为空
     * @param roundTime    本轮时间
     * @return 本轮退出评估结果
     */
    private ExitEvaluation evaluateSingleBatchExit(TornStockVirtualBatchDO batch,
                                                   BigDecimal currentPrice,
                                                   TornStockStrategyFeature15mDO feature,
                                                   LocalDateTime roundTime) {
        BigDecimal position30 = feature != null ? feature.getPosition30() : null;
        BigDecimal low30d = feature != null ? feature.getLow30d() : null;
        BigDecimal high30d = feature != null ? feature.getHigh30d() : null;

        ExitEvaluation evaluation = batchExitService.evaluateExit(
                batch, currentPrice, position30, low30d, high30d, roundTime);

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
        return evaluation;
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
     * @param batch      批次
     * @param metrics    路径指标
     * @param feature    本轮退出特征，可为空
     * @param evaluation 本轮退出评估结果
     * @param roundTime  本轮时间
     * @return 批次标记DO
     */
    private TornStockBatchMarkDO buildBatchMark(TornStockVirtualBatchDO batch,
                                                BatchPathMetrics metrics,
                                                TornStockStrategyFeature15mDO feature,
                                                ExitEvaluation evaluation,
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
        boolean shouldExit = evaluation.shouldExit();
        mark.setFormalDecision(shouldExit ? FORMAL_DECISION_SELL : FORMAL_DECISION_HOLD);
        mark.setFormalReason(shouldExit && evaluation.closeType() != null
                ? evaluation.closeType().getCode() : evaluation.reason());
        mark.setFeatureSnapshot(buildFeatureSnapshot(batch, metrics.currentPrice(), feature));
        return mark;
    }

    /**
     * 固化实际传入退出规则引擎的输入和当前卖出规则版本，供审计与回放使用。
     *
     * @param batch        当前批次
     * @param currentPrice 本轮bar实际价格
     * @param feature      本轮退出特征，可为空
     * @return JSON格式的退出输入快照
     */
    private String buildFeatureSnapshot(TornStockVirtualBatchDO batch,
                                        BigDecimal currentPrice,
                                        TornStockStrategyFeature15mDO feature) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("currentPrice", currentPrice);
        snapshot.put("entryReferencePrice", batch.getEntryReferencePrice());
        snapshot.put("entryTime", batch.getEntryTime());
        snapshot.put("primaryStrategy", batch.getPrimaryStrategy());
        snapshot.put("position30", feature == null ? null : feature.getPosition30());
        snapshot.put("low30d", feature == null ? null : feature.getLow30d());
        snapshot.put("high30d", feature == null ? null : feature.getHigh30d());
        snapshot.put("featureVersion", feature == null ? null : feature.getFeatureVersion());
        snapshot.put("sellRuleVersion", StockRoundTransactionService.SELL_RULE_VERSION);
        return JsonUtils.objToJson(snapshot);
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
     * 将OPEN批次转入DATA_STALE状态,槽位状态置为STALE。
     * <p>
     * 当OPEN批次本轮bar不可用时调用。批次状态置为 DATA_STALE,
     * 关联槽位状态置为 STALE(若存在)。批次仍持有槽位,仅标记数据陈旧。
     *
     * @param batch    待转入DATA_STALE的批次(当前为OPEN)
     * @param slotById 按槽位ID索引的映射
     */
    private void transitionToDataStale(TornStockVirtualBatchDO batch,
                                       Map<Long, TornStockPortfolioSlotDO> slotById) {
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE.getCode());
        if (batch.getSlotId() != null) {
            TornStockPortfolioSlotDO slot = slotById.get(batch.getSlotId());
            if (slot != null) {
                slot.setSlotStatus(StockSlotStatusEnum.STALE.getCode());
            }
        }
        log.info("开放批次[{}]本轮bar不可用,转入DATA_STALE, stocksId={}",
                batch.getBatchNo(), batch.getStocksId());
    }

    /**
     * 将DATA_STALE批次恢复为OPEN状态,槽位状态恢复为OCCUPIED。
     * <p>
     * 当DATA_STALE批次本轮bar恢复可用时调用。批次状态恢复为 OPEN,
     * 关联槽位状态恢复为 OCCUPIED(若存在)。恢复后继续正常的路径更新与退出评估。
     *
     * @param batch    待恢复的批次(当前为DATA_STALE)
     * @param slotById 按槽位ID索引的映射
     */
    private void recoverFromDataStale(TornStockVirtualBatchDO batch,
                                      Map<Long, TornStockPortfolioSlotDO> slotById) {
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        if (batch.getSlotId() != null) {
            TornStockPortfolioSlotDO slot = slotById.get(batch.getSlotId());
            if (slot != null) {
                slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
            }
        }
        log.info("数据陈旧批次[{}]本轮bar恢复,恢复OPEN, stocksId={}",
                batch.getBatchNo(), batch.getStocksId());
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
