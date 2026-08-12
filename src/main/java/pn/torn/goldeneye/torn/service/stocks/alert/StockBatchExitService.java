package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockFormalReasonEnum;
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
     * <p>
     * 时间退出使用调用方传入的 {@code roundTime} 作为基准时间,而非 {@link LocalDateTime#now()},
     * 保证回放与实盘口径一致,避免依赖系统时钟导致的时间漂移。
     *
     * @param batch        待评估批次(须为OPEN状态,含entryReferencePrice与entryTime)
     * @param currentPrice 当前价格
     * @param position30   30日区间位置 (currentPrice - low30) / (high30 - low30),[0,1]
     * @param low30d       30日最低价
     * @param high30d      30日最高价
     * @param roundTime    本轮时间(用于时间退出判断,替代LocalDateTime.now())
     * @return 退出评估结果(shouldExit=true时包含closeType与reason;输入或必要特征不完整时返回dataInsufficient=true的结果)
     */
    public ExitEvaluation evaluateExit(TornStockVirtualBatchDO batch, BigDecimal currentPrice,
                                       BigDecimal position30, BigDecimal low30d, BigDecimal high30d,
                                       LocalDateTime roundTime) {
        Objects.requireNonNull(batch, "批次不能为空");
        Objects.requireNonNull(currentPrice, "当前价格不能为空");
        Objects.requireNonNull(roundTime, "轮次时间不能为空");

        BigDecimal entryReferencePrice = batch.getEntryReferencePrice();
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0) {
            log.warn("批次[{}]入场参考价缺失或非正,返回不可评估", batch.getBatchNo());
            return dataInsufficient("入场参考价缺失");
        }

        BigDecimal netReturn = calculateNetReturn(entryReferencePrice, currentPrice);

        // 1. 目标退出
        ExitEvaluation targetExit = checkTargetExit(netReturn);
        if (targetExit.shouldExit()) {
            log.debug("批次[{}]触发目标退出,netReturn={}", batch.getBatchNo(), netReturn);
            return targetExit;
        }
        if (targetExit.dataInsufficient()) {
            log.warn("批次[{}]目标退出不可评估,netReturn={}", batch.getBatchNo(), netReturn);
            return targetExit;
        }

        // 2. 风险退出
        ExitEvaluation riskExit = checkRiskExit(netReturn);
        if (riskExit.shouldExit()) {
            log.debug("批次[{}]触发风险退出,netReturn={}", batch.getBatchNo(), netReturn);
            return riskExit;
        }
        if (riskExit.dataInsufficient()) {
            log.warn("批次[{}]风险退出不可评估,netReturn={}", batch.getBatchNo(), netReturn);
            return riskExit;
        }

        // 3. 时间退出(使用roundTime而非LocalDateTime.now(),保证回放一致性)
        ExitEvaluation timeExit = checkTimeExit(batch.getEntryTime(), roundTime);
        if (timeExit.shouldExit()) {
            log.debug("批次[{}]触发时间退出,entryTime={},roundTime={}", batch.getBatchNo(), batch.getEntryTime(), roundTime);
            return timeExit;
        }
        if (timeExit.dataInsufficient()) {
            log.warn("批次[{}]时间退出不可评估,entryTime={}", batch.getBatchNo(), batch.getEntryTime());
            return timeExit;
        }

        // 4. 区间恢复退出
        ExitEvaluation rangeExit = checkRangeExit(batch.getPrimaryStrategy(), netReturn, position30, low30d, high30d);
        if (rangeExit.shouldExit()) {
            log.debug("批次[{}]触发区间恢复退出,netReturn={},position30={}", batch.getBatchNo(), netReturn, position30);
            return rangeExit;
        }
        if (rangeExit.dataInsufficient()) {
            log.warn("批次[{}]区间退出不可评估,primaryStrategy={},position30={},low30d={},high30d={}",
                    batch.getBatchNo(), batch.getPrimaryStrategy(), position30, low30d, high30d);
            return rangeExit;
        }

        return hold("未命中任何退出规则");
    }

    // ==================== 四种退出规则 ====================

    /**
     * 检查目标退出
     * <p>
     * netReturn >= +0.8%({@link #TARGET_RETURN_THRESHOLD})时触发,关闭类型为 CLOSED_TARGET。
     *
     * @param netReturn 当前净收益率
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false;净收益率为空(基础输入缺失)时返回dataInsufficient=true
     */
    public ExitEvaluation checkTargetExit(BigDecimal netReturn) {
        if (netReturn == null) {
            return dataInsufficient("净收益率为空,基础输入缺失");
        }
        if (netReturn.compareTo(TARGET_RETURN_THRESHOLD) >= 0) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_TARGET,
                    StockFormalReasonEnum.SELL_TARGET_REACHED.getCode(),
                    "目标退出: netReturn=" + netReturn + " >= " + TARGET_RETURN_THRESHOLD);
        }
        return hold("未达到目标退出阈值");
    }

    /**
     * 检查风险退出
     * <p>
     * netReturn <= -1.5%({@link #RISK_RETURN_THRESHOLD})时触发,关闭类型为 CLOSED_RISK。
     *
     * @param netReturn 当前净收益率
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false;净收益率为空(基础输入缺失)时返回dataInsufficient=true
     */
    public ExitEvaluation checkRiskExit(BigDecimal netReturn) {
        if (netReturn == null) {
            return dataInsufficient("净收益率为空,基础输入缺失");
        }
        if (netReturn.compareTo(RISK_RETURN_THRESHOLD) <= 0) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_RISK,
                    StockFormalReasonEnum.SELL_HARD_RISK.getCode(),
                    "风险退出: netReturn=" + netReturn + " <= " + RISK_RETURN_THRESHOLD);
        }
        return hold("未达到风险退出阈值");
    }

    /**
     * 检查最长持有时间退出
     * <p>
     * 持有时间 >= {@link #MAX_HOLD_DAYS}天时触发,关闭类型为 CLOSED_TIME。
     *
     * @param entryTime   入场时间
     * @param currentTime 当前时间
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false;入场或当前时间为空(基础输入缺失)时返回dataInsufficient=true
     */
    public ExitEvaluation checkTimeExit(LocalDateTime entryTime, LocalDateTime currentTime) {
        if (entryTime == null || currentTime == null) {
            return dataInsufficient("入场时间或当前时间为空,基础输入缺失");
        }
        long holdDays = Duration.between(entryTime, currentTime).toDays();
        if (holdDays >= MAX_HOLD_DAYS) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_TIME,
                    StockFormalReasonEnum.SELL_MAX_HOLD.getCode(),
                    "时间退出: 持有" + holdDays + "天 >= " + MAX_HOLD_DAYS + "天");
        }
        return hold("未达到最长持有时间");
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
     * 对RANGE批次,该策略区间特征属于必要输入: {@code low30d/high30d/position30}
     * 任一为空或 {@code high30d <= low30d} 时视为不可评估,返回 dataInsufficient=true,
     * 不得把缺失解释为区间规则未命中。非RANGE批次区间规则不适用(NOT_APPLICABLE),
     * 返回普通hold,由上层允许写通用HOLD。
     * position30 = (currentPrice - low30d) / (high30d - low30d),由调用方预算后传入。
     *
     * @param primaryStrategy 主策略标识
     * @param netReturn       当前净收益率
     * @param position30      30日区间位置(调用方预算好);为空时不触发区间退出
     * @param low30d          30日最低价
     * @param high30d         30日最高价
     * @return 命中时返回shouldExit=true的评估结果;未命中返回shouldExit=false;RANGE必要特征缺失/无效时返回dataInsufficient=true
     */
    public ExitEvaluation checkRangeExit(String primaryStrategy, BigDecimal netReturn,
                                         BigDecimal position30, BigDecimal low30d, BigDecimal high30d) {
        if (!RANGE_LOWER_BUY_STRATEGY.equals(primaryStrategy)) {
            return hold("主策略非区间下沿买入");
        }

        // RANGE批次: 区间特征属于必要输入,缺失或无效视为不可评估(fail-closed)
        if (low30d == null || high30d == null) {
            return dataInsufficient("30日高低价为空,区间必要特征缺失");
        }
        if (high30d.compareTo(low30d) <= 0) {
            return dataInsufficient("30日高低价无效(high30<=low30)");
        }
        if (position30 == null) {
            return dataInsufficient("position30为空,区间必要特征缺失");
        }

        if (netReturn == null || netReturn.signum() <= 0) {
            return hold("净收益率非正");
        }

        if (position30.compareTo(RANGE_POSITION_THRESHOLD) >= 0) {
            return new ExitEvaluation(true, StockCloseTypeEnum.CLOSED_RANGE,
                    StockFormalReasonEnum.SELL_RANGE_RECOVERED.getCode(),
                    "区间恢复退出: position30=" + position30 + " >= " + RANGE_POSITION_THRESHOLD);
        }
        return hold("position30未达区间恢复阈值");
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
     * 构建未触发退出的评估结果。
     * <p>
     * 未触发任何退出规则时,正式决定为HOLD,使用冻结的通用HOLD原因编码
     * {@link StockFormalReasonEnum#HOLD_NO_EXIT_TRIGGERED}。说明文本仅用于日志与调试,
     * 不复用BatchMark.formal_reason字段。
     *
     * @param reason 未退出原因描述(仅日志用途)
     * @return shouldExit=false的评估结果
     */
    private static ExitEvaluation hold(String reason) {
        return new ExitEvaluation(false, null,
                StockFormalReasonEnum.HOLD_NO_EXIT_TRIGGERED.getCode(), reason);
    }

    /**
     * 构建不可评估的评估结果。
     * <p>
     * 输入或该策略必要特征不完整时返回,表示当前无法完成有效评估,禁止写成通用HOLD
     * ({@link StockFormalReasonEnum#HOLD_NO_EXIT_TRIGGERED}),应由上层转入数据不足/不可评估路径(如DATA_STALE)。
     *
     * @param reason 不可评估原因描述(仅日志用途)
     * @return dataInsufficient=true且shouldExit=false的评估结果
     */
    private static ExitEvaluation dataInsufficient(String reason) {
        return new ExitEvaluation(false, null, null, reason);
    }

    /**
     * 退出评估结果
     * <p>
     * 封装退出评估的判定输出。
     * <ul>
     *   <li>shouldExit=true时closeType与reasonCode必有值,reasonCode为冻结的SELL原因编码;</li>
     *   <li>shouldExit=false且dataInsufficient=false时closeType为null,
     *       reasonCode为冻结的HOLD通用编码,reason描述未命中原因(仅日志用途);</li>
     *   <li>shouldExit=false且dataInsufficient=true时closeType与reasonCode均为null,
     *       reason描述不可评估原因,禁止写成通用HOLD。</li>
     * </ul>
     *
     * @param shouldExit 是否应退出
     * @param closeType  关闭类型(shouldExit=true时非空)
     * @param reasonCode 冻结的正式决定原因编码(SELL_* 或 HOLD_* 前缀编码;不可评估时为null)
     * @param reason     退出/未退出/不可评估原因描述(仅日志用途)
     */
    public record ExitEvaluation(boolean shouldExit, StockCloseTypeEnum closeType,
                                 String reasonCode, String reason) {

        /**
         * 是否为不可评估结果(输入或必要特征不完整)。
         *
         * @return 不可评估时返回true
         */
        public boolean dataInsufficient() {
            return !shouldExit && reasonCode == null;
        }
    }
}
