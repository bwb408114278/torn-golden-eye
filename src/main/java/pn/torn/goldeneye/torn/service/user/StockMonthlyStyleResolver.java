package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 私聊{@code Stock分析}指令的月度风格解析组件(只读)。
 * <p>
 * 按 {@code stock_personality_monthly_calibration.md} §13 消费口径解析每支股票的
 * 月度风格/成熟度/风险,选月算法为「最近一条CONFIRMED且 ≤ 目标月」:
 * <ul>
 *   <li>当月有CONFIRMED行 → 正常使用,理由文案标注生效月份;</li>
 *   <li>最近CONFIRMED早于目标月恰好1个月 → 沿用该月并留痕(如"使用 2026-09 风格(2026-10 未生成)");</li>
 *   <li>连续2个月无新CONFIRMED月份 → 停止推荐(该股全部HOLD)+告警,防止沿用退化为固定死值;</li>
 *   <li>无任何CONFIRMED行,或风格为{@code ALPHA_NOT_EVALUATED}等伪值 → 不推荐+告警,
 *       禁止默认{@code STEADY};</li>
 *   <li>当月DRAFT等同未生成,不作为风格来源。</li>
 * </ul>
 * 边界冻结:本组件只被 {@link StockTradeStrategyService} 注入,不得被
 * {@code torn/service/stocks/alert/alpha/**}、{@code torn/service/stocks/alert/market/**}
 * 引用(月度风格不进入α任何决策路径)。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockMonthlyStyleResolver {

    /**
     * 沿用上限:连续该月数无新CONFIRMED月份后停止推荐
     */
    private static final long MAX_CARRY_OVER_MONTHS = 1L;

    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final TornStocksDAO tornStocksDao;

    /**
     * 解析结果 - 单支股票的月度风格消费口径。
     *
     * @param styleAvailable 是否存在可用月度风格(false时该股不推荐)
     * @param personality    评分参数档位(styleAvailable=true时非空)
     * @param maturity       成熟度(styleAvailable=true时非空)
     * @param riskLevel      风险等级(styleAvailable=true时非空;NONE无影响,MEDIUM仅展示,HIGH门槛+10)
     * @param effectiveMonth 实际生效月份(styleAvailable=true时非空)
     * @param carriedOver    是否沿用更早月份(留痕文案由调用方生成)
     * @param blockedReason  不推荐原因(styleAvailable=false时非空,用于告警与理由文案)
     */
    public record ResolvedMonthlyStyle(
            boolean styleAvailable,
            StockPersonalityEnum personality,
            StockMaturityEnum maturity,
            StockRiskLevelEnum riskLevel,
            YearMonth effectiveMonth,
            boolean carriedOver,
            String blockedReason) {
    }

    /**
     * 批量解析全部股票在目标月的月度风格。
     * <p>
     * 单次查询每股最近一条CONFIRMED行,再在Java侧做选月/沿用/停推判定;
     * 有行但不可用的股票由聚合ERROR告警覆盖,无任何CONFIRMED行的股票逐股补ERROR
     * (月度规范§13.4,使「回复文案+应用日志」两渠道齐全)。
     *
     * @param targetMonth 目标生效月份(当月1日)
     * @return 股票ID到解析结果的映射;无CONFIRMED行的股票不进入映射,由消费方按无风格停止推荐
     */
    public Map<Integer, ResolvedMonthlyStyle> resolveAll(LocalDate targetMonth) {
        List<TornStockMonthlyStateDO> latestConfirmed =
                monthlyStateDao.selectLatestConfirmedUpToMonth(targetMonth);
        Map<Integer, TornStockMonthlyStateDO> byStock = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(latestConfirmed)) {
            for (TornStockMonthlyStateDO state : latestConfirmed) {
                if (state != null && state.getStocksId() != null) {
                    byStock.put(state.getStocksId(), state);
                }
            }
        }
        for (TornStocksDO stock : tornStocksDao.list()) {
            if (stock != null && stock.getId() != null && !byStock.containsKey(stock.getId())) {
                log.error("月度风格解析-股票[{}]({})无任何CONFIRMED月度状态,本次不推荐",
                        stock.getStocksShortname(), stock.getId());
            }
        }

        YearMonth target = YearMonth.from(targetMonth);
        Map<Integer, ResolvedMonthlyStyle> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, TornStockMonthlyStateDO> entry : byStock.entrySet()) {
            result.put(entry.getKey(), resolveSingle(target, entry.getValue()));
        }
        if (result.values().stream().anyMatch(style -> !style.styleAvailable())) {
            List<String> blocked = result.entrySet().stream()
                    .filter(e -> !e.getValue().styleAvailable())
                    .map(e -> e.getKey() + "(" + e.getValue().blockedReason() + ")")
                    .toList();
            log.error("月度风格解析-以下股票无可用月度风格,本次不推荐: {}", String.join(", ", blocked));
        }
        return result;
    }

    /**
     * 对单支股票的最近CONFIRMED行做选月与可用性判定。
     *
     * @param target 目标月份
     * @param state  该股最近一条CONFIRMED月度状态(非空)
     * @return 解析结果
     */
    private ResolvedMonthlyStyle resolveSingle(YearMonth target, TornStockMonthlyStateDO state) {
        if (state.getEffectiveMonth() == null) {
            return blocked("月度生效月份缺失");
        }
        YearMonth effective = YearMonth.from(state.getEffectiveMonth());
        long monthsBetween = ChronoUnit.MONTHS.between(effective, target);
        if (monthsBetween > MAX_CARRY_OVER_MONTHS) {
            return blocked("连续 " + monthsBetween + " 个月未生成（最近 " + effective + "）");
        }

        StockPersonalityEnum personality = parsePersonality(state.getStrategyFitPrior());
        if (personality == null) {
            return blocked(codeBlockedReason("风格",
                    StockStrategyFitEnum.ALPHA_NOT_EVALUATED.getCode(), state.getStrategyFitPrior()));
        }
        StockMaturityEnum maturity = parseMaturity(state.getMaturity());
        if (maturity == null) {
            return blocked(codeBlockedReason("成熟度",
                    StockMaturityEnum.ALPHA_NOT_EVALUATED.getCode(), state.getMaturity()));
        }
        StockRiskLevelEnum riskLevel = parseRisk(state.getRiskLevel());
        if (riskLevel == null) {
            return blocked(codeBlockedReason("风险",
                    StockRiskLevelEnum.ALPHA_NOT_EVALUATED.getCode(), state.getRiskLevel()));
        }
        return new ResolvedMonthlyStyle(true, personality, maturity, riskLevel,
                effective, monthsBetween > 0, null);
    }

    /**
     * 维度编码不可用原因文案:α伪值按维度转中文,未知编码回显原值便于排障,
     * 不得在用户可见文案回显α英文编码(设计§4.4.1第5条)。
     *
     * @param dimension 维度名(风格/成熟度/风险)
     * @param alphaCode 该维度枚举的α伪值编码
     * @param code      实际编码
     * @return 不推荐原因文案
     */
    private static String codeBlockedReason(String dimension, String alphaCode, String code) {
        if (alphaCode.equals(code)) {
            return dimension + "编码 α 未评估";
        }
        return dimension + "编码不可识别：" + code;
    }

    /**
     * 构建不推荐结果。
     *
     * @param reason 不推荐原因
     * @return styleAvailable=false的解析结果
     */
    private ResolvedMonthlyStyle blocked(String reason) {
        return new ResolvedMonthlyStyle(false, null, null, null, null, false, reason);
    }

    /**
     * 解析月度风格编码为评分参数档位。
     * <p>
     * {@code StockStrategyFitEnum}六类与{@code StockPersonalityEnum}业务编码1:1同名;
     * {@code ALPHA_NOT_EVALUATED}是α专用伪值,私聊解析遇到它必须视为无可用风格。
     *
     * @param strategyFitPrior 月度状态风格编码
     * @return 评分参数档位;空/伪值/未知编码返回null(fail-closed,不默认STEADY)
     */
    private StockPersonalityEnum parsePersonality(String strategyFitPrior) {
        if (strategyFitPrior == null || strategyFitPrior.isBlank()) {
            return null;
        }
        StockStrategyFitEnum fit;
        try {
            fit = StockStrategyFitEnum.fromCode(strategyFitPrior);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (fit == StockStrategyFitEnum.ALPHA_NOT_EVALUATED) {
            return null;
        }
        try {
            return StockPersonalityEnum.valueOf(fit.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析成熟度编码。
     * <p>
     * M0_UNMATURE按消费口径由调用方强制HOLD(月度规范§13.2),M1_EARLY可用且门槛+10,
     * 因此本方法只拒绝空值、未知编码与α伪值,不使用{@code isUsable()}过滤
     * (该方法只认M2+,会把需门槛加成的M1_EARLY误判为不可用)。
     *
     * @param maturity 成熟度编码
     * @return 成熟度枚举;空、α伪值或未知编码返回null
     */
    private StockMaturityEnum parseMaturity(String maturity) {
        if (maturity == null || maturity.isBlank()) {
            return null;
        }
        try {
            StockMaturityEnum parsed = StockMaturityEnum.fromCode(maturity);
            return parsed == StockMaturityEnum.ALPHA_NOT_EVALUATED ? null : parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析风险等级编码。
     *
     * @param riskLevel 风险等级编码
     * @return 风险等级枚举;空、α伪值或未知编码返回null
     */
    private StockRiskLevelEnum parseRisk(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return null;
        }
        try {
            StockRiskLevelEnum parsed = StockRiskLevelEnum.fromCode(riskLevel);
            return parsed == StockRiskLevelEnum.ALPHA_NOT_EVALUATED ? null : parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
