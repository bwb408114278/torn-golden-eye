package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 股票批次退出评估服务 - 对正式开放批次计算目标、风险、区间和时间退出
 * <p>
 * 针对 {@link TornStockVirtualBatchDO} 的 OPEN 状态批次,按固定优先级顺序评估四种退出规则,
 * 返回首个命中的退出类型与原因。所有净收益计算统一扣除 {@value #SELL_FEE_RATE_TEXT} 卖出手续费。
 *
 * <h3>退出判断顺序</h3>
 * <ol>
 *   <li>目标退出: netReturn >= +0.8% -> {@link StockCloseTypeEnum#CLOSED_TARGET}</li>
 *   <li>风险退出: netReturn <= -1.5% -> {@link StockCloseTypeEnum#CLOSED_RISK}</li>
 *   <li>时间退出: 持有时间 >= 14天 -> {@link StockCloseTypeEnum#CLOSED_TIME}</li>
 *   <li>区间恢复退出: RANGE_LOWER_BUY策略 AND netReturn > 0 AND position30 >= 0.60
 *       -> {@link StockCloseTypeEnum#CLOSED_RANGE}(high30==low30时fail-closed)</li>
 * </ol>
 *
 * <p>netReturn = currentPrice / entryReferencePrice × 0.999 - 1
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBatchExitService {
    /**
     * 目标退出阈值(+0.8%)
     */
    public static final BigDecimal TARGET_RETURN_THRESHOLD = new BigDecimal("0.008");
    /**
     * 风险退出阈值(-1.5%)
     */
    public static final BigDecimal RISK_RETURN_THRESHOLD = new BigDecimal("-0.015");
    /**
     * 最长持有天数
     */
    public static final int MAX_HOLD_DAYS = 14;
    /**
     * 区间恢复退出 - position30阈值
     */
    public static final BigDecimal RANGE_POSITION_THRESHOLD = new BigDecimal("0.60");
    /**
     * 卖出费率(0.1%手续费,实得99.9%)
     */
    public static final BigDecimal SELL_FEE_RATE = new BigDecimal("0.999");
    /**
     * 卖出费率明文(仅用于Javadoc展示)
     */
    static final String SELL_FEE_RATE_TEXT = "0.1%";
    /**
     * 区间下沿买入策略标识
     */
    public static final String RANGE_LOWER_BUY_STRATEGY = "RANGE_LOWER_BUY";
    /**
     * 金额与收益率计算精度
     */
    private static final int MATH_SCALE = 18;

    /**
     * 评估批次退出条件
     * <p>
     * 按目标 -> 风险 -> 时间 -> 区间恢复的固定优先级顺序检查,返回首个命中的退出结果。
     * 若四种退出均未命中,返回 shouldExit=false 的空结果。
     *
     * @param batch        待评估批次(须为OPEN状态,含entryReferencePrice与entryTime)
     * @param currentPrice 当前价格
     * @param position30   30日区间位置 (currentPrice - low30) / (high30 - low30),[0,1]
     * @param low30d       30日最低价
     * @param high30d      30日最高价
     * @return 退出评估结果(shouldExit=true时包含closeType与reason)
     */
    public ExitEvaluation evaluateExit(TornStockVirtualBatchDO batch, BigDecimal currentPrice,
                                       BigDecimal position30, BigDecimal low30d, BigDecimal high30d) {
        Objects.requireNonNull(batch, "批次不能为空");
        Objects.requireNonNull(currentPrice, "当前价格不能为空");

        BigDecimal entryReferencePrice = batch.getEntryReferencePrice();
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0) {
            log.warn("批次[{}]入场参考价缺失或非正,跳过退出评估", batch.getBatchNo());
            return new ExitEvaluation(false, null, "入场参考价缺失");
        }

        BigDecimal netReturn = calculateNetReturn(entryReferencePrice, currentPrice);

        // 1. 目标退出
        ExitEvaluation targetExit = checkTargetExit(netReturn);
        if (targetExit.shouldExit()) {
            log.debug("批次[{}]触发目标退出,netReturn={}", batch.getBatchNo(), netReturn);
            return targetExit;
        }

        // 2. 风险退出
        ExitEvaluation riskExit = checkRiskExit(netReturn);
        if (riskExit.shouldExit()) {
            log.debug("批次[{}]触发风险退出,netReturn={}", batch.getBatchNo(), netReturn);
            return riskExit;
        }

        // 3. 时间退出
        ExitEvaluation timeExit = checkTimeExit(batch.getEntryTime(), LocalDateTime.now());
        if (timeExit.shouldExit()) {
            log.debug("批次[{}]触发时间退出,entryTime={}", batch.getBatchNo(), batch.getEntryTime());
            return timeExit;
        }

        // 4. 区间恢复退出
        ExitEvaluation rangeExit = checkRangeExit(batch.getPrimaryStrategy(), netReturn, position30, low30d, high30d);
        if (rangeExit.shouldExit()) {
            log.debug("批次[{}]触发区间恢复退出,netReturn={},position30={}", batch.getBatchNo(), netReturn, position30);
            return rangeExit;
        }

        return new ExitEvaluation(false, null, "未命中任何退出规则");
    }

    // ==================== 四种退出规则 ====================

    /**
     * 检查目标退出
     * <p>
     * netReturn >= +0.8%({@link #TARGET_RETURN_THRESHOLD})时触发,关闭类型为 CLOSED_TARGET。
     *
     * @param netReturn 当前净收益率
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false
     */
    public ExitEvaluation checkTargetExit(BigDecimal netReturn) {
        if (netReturn == null) {
            return new ExitEvaluation(false, null, "净收益率为空");
        }
        if (netReturn.compareTo(TARGET_RETURN_THRESHOLD) >= 0) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_TARGET,
                    "目标退出: netReturn=" + netReturn + " >= " + TARGET_RETURN_THRESHOLD);
        }
        return new ExitEvaluation(false, null, "未达到目标退出阈值");
    }

    /**
     * 检查风险退出
     * <p>
     * netReturn <= -1.5%({@link #RISK_RETURN_THRESHOLD})时触发,关闭类型为 CLOSED_RISK。
     *
     * @param netReturn 当前净收益率
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false
     */
    public ExitEvaluation checkRiskExit(BigDecimal netReturn) {
        if (netReturn == null) {
            return new ExitEvaluation(false, null, "净收益率为空");
        }
        if (netReturn.compareTo(RISK_RETURN_THRESHOLD) <= 0) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_RISK,
                    "风险退出: netReturn=" + netReturn + " <= " + RISK_RETURN_THRESHOLD);
        }
        return new ExitEvaluation(false, null, "未达到风险退出阈值");
    }

    /**
     * 检查最长持有时间退出
     * <p>
     * 持有时间 >= {@link #MAX_HOLD_DAYS}天时触发,关闭类型为 CLOSED_TIME。
     *
     * @param entryTime   入场时间
     * @param currentTime 当前时间
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false
     */
    public ExitEvaluation checkTimeExit(LocalDateTime entryTime, LocalDateTime currentTime) {
        if (entryTime == null || currentTime == null) {
            return new ExitEvaluation(false, null, "入场时间或当前时间为空");
        }
        long holdDays = Duration.between(entryTime, currentTime).toDays();
        if (holdDays >= MAX_HOLD_DAYS) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_TIME,
                    "时间退出: 持有" + holdDays + "天 >= " + MAX_HOLD_DAYS + "天");
        }
        return new ExitEvaluation(false, null, "未达到最长持有时间");
    }

    /**
     * 检查区间恢复退出
     * <p>
     * 同时满足以下条件时触发,关闭类型为 CLOSED_RANGE:
     * <ol>
     *   <li>primaryStrategy == "RANGE_LOWER_BUY"</li>
     *   <li>netReturn > 0</li>
     *   <li>position30 >= 0.60</li>
     * </ol>
     * 当 high30d == low30d 时 fail-closed,不触发区间退出(避免除零)。
     * position30 = (currentPrice - low30d) / (high30d - low30d)。
     *
     * @param primaryStrategy 主策略标识
     * @param netReturn       当前净收益率
     * @param position30      30日区间位置(调用方预算好);为空时尝试用low30d/high30d重算
     * @param low30d          30日最低价
     * @param high30d         30日最高价
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false
     */
    public ExitEvaluation checkRangeExit(String primaryStrategy, BigDecimal netReturn,
                                         BigDecimal position30, BigDecimal low30d, BigDecimal high30d) {
        if (!RANGE_LOWER_BUY_STRATEGY.equals(primaryStrategy)) {
            return new ExitEvaluation(false, null, "主策略非区间下沿买入");
        }
        if (netReturn == null || netReturn.signum() <= 0) {
            return new ExitEvaluation(false, null, "净收益率非正");
        }

        // fail-closed: high30 == low30 时不触发
        if (low30d == null || high30d == null) {
            return new ExitEvaluation(false, null, "30日高低价为空");
        }
        if (high30d.compareTo(low30d) == 0) {
            return new ExitEvaluation(false, null, "30日高低价相等,fail-closed");
        }

        // 若调用方未预算position30,则用low30d/high30d重算
        BigDecimal effectivePosition30 = position30;
        if (effectivePosition30 == null) {
            effectivePosition30 = calculatePosition30(low30d, high30d);
            if (effectivePosition30 == null) {
                return new ExitEvaluation(false, null, "position30计算失败");
            }
        }

        if (effectivePosition30.compareTo(RANGE_POSITION_THRESHOLD) >= 0) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_RANGE,
                    "区间恢复退出: position30=" + effectivePosition30 + " >= " + RANGE_POSITION_THRESHOLD);
        }
        return new ExitEvaluation(false, null, "position30未达区间恢复阈值");
    }

    // ==================== 内部计算方法 ====================

    /**
     * 计算扣费后净收益率
     * <p>
     * netReturn = currentPrice / entryReferencePrice × 0.999 - 1
     *
     * @param entryReferencePrice 入场参考价(>0)
     * @param currentPrice        当前价格
     * @return 净收益率;入场价为非正数时返回null
     */
    private BigDecimal calculateNetReturn(BigDecimal entryReferencePrice, BigDecimal currentPrice) {
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0 || currentPrice == null) {
            return null;
        }
        return currentPrice
                .divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP)
                .multiply(SELL_FEE_RATE)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 计算30日区间位置
     * TODO 阶段B轮次处理时补充currentPrice参数，当前该方法永远返回null，由调用方预算position30
     * <p>
     * position30 = (currentPrice - low30) / (high30 - low30)，
     * 调用前需保证 high30 != low30。当前价由调用方传入,此方法仅用高低价无法计算，
     * 故返回null提示调用方传入预算好的position30。保留此方法以备内部扩展。
     *
     * @param low30d  30日最低价
     * @param high30d 30日最高价
     * @return null(需调用方传入position30)
     */
    private BigDecimal calculatePosition30(BigDecimal low30d, BigDecimal high30d) {
        // 此方法签名仅含高低价,无法独立计算position30(缺currentPrice)
        // 保留为扩展点,当前返回null由调用方负责预算
        return null;
    }

    /**
     * 退出评估结果
     * <p>
     * 封装退出评估的判定输出。shouldExit为true时closeType与reason必有值;
     * shouldExit为false时closeType为null,reason描述未命中原因。
     *
     * @param shouldExit 是否应退出
     * @param closeType  关闭类型(shouldExit=true时非空)
     * @param reason     退出/未退出原因描述
     */
    public record ExitEvaluation(boolean shouldExit, StockCloseTypeEnum closeType, String reason) {
    }
}
