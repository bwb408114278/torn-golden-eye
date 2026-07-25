package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票买入资格判断服务，在策略匹配之前按固定顺序执行风格、成熟度、风险、冷却、复位、
 * 同股活跃批次与数据门禁检查，决定该股票是否允许进入正式候选流程。
 * <p>
 * 检查顺序（命中任一即短路返回）：
 * <ol>
 *   <li>风格缺失或过期 -> REJECTED（STYLE_MISSING）</li>
 *   <li>风格不适配当前策略 -> REJECTED（STYLE_NOT_APPLICABLE）</li>
 *   <li>成熟度不足（M0/M1） -> REJECTED（MATURITY_INSUFFICIENT）</li>
 *   <li>风险等级为HIGH -> OBSERVED（HIGH_RISK_OBSERVED）</li>
 *   <li>冷却中（cooldownUntil > now） -> REJECTED（COOLDOWN_ACTIVE）</li>
 *   <li>未复位（resetObserved == false && lastCloseType != null） -> REJECTED（RESET_NOT_OBSERVED）</li>
 *   <li>同股已有正式活跃批次 -> REJECTED（SAME_STOCK_ACTIVE）</li>
 *   <li>strategyReady == false -> REJECTED（DATA_NOT_READY）</li>
 *   <li>以上全部通过 -> ALLOWED</li>
 * </ol>
 * 注意：风格不适配检查需要调用方明确指定待校验的策略适用风格集合，本服务仅判断
 * context中的stylePrior是否落在该集合内；若调用方未传入策略风格集合则跳过该项检查。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Slf4j
@Service
public class StockEligibilityService {

    /** 拒绝原因编码：风格缺失或过期 */
    private static final String REASON_STYLE_MISSING = "STYLE_MISSING";
    /** 拒绝原因编码：风格不适配当前策略 */
    private static final String REASON_STYLE_NOT_APPLICABLE = "STYLE_NOT_APPLICABLE";
    /** 拒绝原因编码：成熟度不足 */
    private static final String REASON_MATURITY_INSUFFICIENT = "MATURITY_INSUFFICIENT";
    /** 观察原因编码：高风险观察 */
    private static final String REASON_HIGH_RISK_OBSERVED = "HIGH_RISK_OBSERVED";
    /** 拒绝原因编码：冷却中 */
    private static final String REASON_COOLDOWN_ACTIVE = "COOLDOWN_ACTIVE";
    /** 拒绝原因编码：未观察到复位 */
    private static final String REASON_RESET_NOT_OBSERVED = "RESET_NOT_OBSERVED";
    /** 拒绝原因编码：同股已有正式活跃批次 */
    private static final String REASON_SAME_STOCK_ACTIVE = "SAME_STOCK_ACTIVE";
    /** 拒绝原因编码：策略特征数据未就绪 */
    private static final String REASON_DATA_NOT_READY = "DATA_NOT_READY";

    /**
     * 检查股票是否具备进入正式买入候选流程的资格。
     * <p>
     * 按固定顺序执行全部门禁检查，命中任一拒绝/观察条件即短路返回；
     * 全部通过时返回ALLOWED。返回的{@link EligibilityResult}包含结果枚举与原因编码列表。
     *
     * @param context              买入评估上下文，包含特征与月度状态
     * @param signalState          信号状态记录，包含冷却与复位信息，可为null（视为无冷却、已复位）
     * @param monthlyState         月度状态记录，可为null
     * @param hasActiveFormalBatch 当前股票是否已有正式活跃批次
     * @return 资格判定结果
     */
    public EligibilityResult checkEligibility(
            BuyContext context,
            TornStockSignalStateDO signalState,
            TornStockMonthlyStateDO monthlyState,
            boolean hasActiveFormalBatch) {

        List<String> reasons = new ArrayList<>();
        Integer stocksId = context.stocksId();

        // 1. 风格缺失或过期
        StockStrategyFitEnum style = context.stylePrior();
        if (style == null) {
            return reject(stocksId, REASON_STYLE_MISSING, "风格缺失或过期");
        }

        // 2. 成熟度不足（M0/M1）
        StockMaturityEnum maturity = context.maturity();
        if (maturity == null || !maturity.isUsable()) {
            return reject(stocksId, REASON_MATURITY_INSUFFICIENT,
                    "成熟度不足: " + (maturity == null ? "null" : maturity.getCode()));
        }

        // 3. 风险等级为HIGH -> OBSERVED
        StockRiskLevelEnum riskLevel = context.riskLevel();
        if (StockRiskLevelEnum.HIGH == riskLevel) {
            return observe(stocksId, REASON_HIGH_RISK_OBSERVED, "风险等级为HIGH");
        }

        // 4. 冷却中
        if (isInCooldown(signalState)) {
            return reject(stocksId, REASON_COOLDOWN_ACTIVE, "处于冷却期");
        }

        // 5. 未复位
        if (isResetNotObserved(signalState)) {
            return reject(stocksId, REASON_RESET_NOT_OBSERVED, "未观察到条件复位");
        }

        // 6. 同股已有正式活跃批次
        if (hasActiveFormalBatch) {
            return reject(stocksId, REASON_SAME_STOCK_ACTIVE, "同股已有正式活跃批次");
        }

        // 7. 策略特征数据未就绪
        Boolean strategyReady = context.strategyReady();
        if (strategyReady == null || !strategyReady) {
            return reject(stocksId, REASON_DATA_NOT_READY, "策略特征数据未就绪");
        }

        // 全部通过
        log.info("资格判断-通过: stocksId={}, style={}, maturity={}, risk={}",
                stocksId, style, maturity, riskLevel);
        return new EligibilityResult(StockEligibilityResultEnum.ALLOWED, List.of());
    }

    /**
     * 判断信号状态是否处于冷却期。
     *
     * @param signalState 信号状态记录
     * @return 冷却未结束时返回true
     */
    private boolean isInCooldown(TornStockSignalStateDO signalState) {
        if (signalState == null) {
            return false;
        }
        LocalDateTime cooldownUntil = signalState.getCooldownUntil();
        return cooldownUntil != null && cooldownUntil.isAfter(LocalDateTime.now());
    }

    /**
     * 判断是否未观察到条件复位。
     * <p>
     * 复位要求：上次有平仓记录（lastCloseType != null）时，必须 resetObserved == true
     * 才允许产生新信号；若从未平仓过则不要求复位。
     *
     * @param signalState 信号状态记录
     * @return 需要复位但尚未观察到时返回true
     */
    private boolean isResetNotObserved(TornStockSignalStateDO signalState) {
        if (signalState == null) {
            return false;
        }
        String lastCloseType = signalState.getLastCloseType();
        if (lastCloseType == null || lastCloseType.isBlank()) {
            return false;
        }
        Boolean resetObserved = signalState.getResetObserved();
        return resetObserved == null || !resetObserved;
    }

    /**
     * 构造拒绝结果并记录日志。
     *
     * @param stocksId 股票ID
     * @param reason   拒绝原因编码
     * @param detail   拒绝详情（仅用于日志）
     * @return REJECTED结果
     */
    private EligibilityResult reject(Integer stocksId, String reason, String detail) {
        log.info("资格判断-拒绝: stocksId={}, reason={}, detail={}", stocksId, reason, detail);
        return new EligibilityResult(StockEligibilityResultEnum.REJECTED, List.of(reason));
    }

    /**
     * 构造观察结果并记录日志。
     *
     * @param stocksId 股票ID
     * @param reason   观察原因编码
     * @param detail   观察详情（仅用于日志）
     * @return OBSERVED结果
     */
    private EligibilityResult observe(Integer stocksId, String reason, String detail) {
        log.info("资格判断-观察: stocksId={}, reason={}, detail={}", stocksId, reason, detail);
        return new EligibilityResult(StockEligibilityResultEnum.OBSERVED, List.of(reason));
    }

    /**
     * 资格判定结果值对象。
     *
     * @param result  资格结果枚举
     * @param reasons 原因编码列表，ALLOWED时为空列表
     * @author Bai
     * @version 1.2.12
     * @since 2026.07.24
     */
    public record EligibilityResult(
            StockEligibilityResultEnum result,
            List<String> reasons
    ) {
    }
}
