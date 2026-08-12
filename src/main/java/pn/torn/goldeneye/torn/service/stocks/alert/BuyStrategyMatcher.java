package pn.torn.goldeneye.torn.service.stocks.alert;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 买入策略匹配器 - 遍历全部买入策略,返回命中的策略列表、主策略(质量分最高)与最优质量分。
 * <p>
 * 纯规则组件,无DAO、无事务、无写操作。对每个策略先调用 {@link StockBuyStrategy#isApplicableStyle}
 * 校验风格适配,再调用 {@link StockBuyStrategy#matches} 判断是否命中。
 * <p>
 * 主策略在质量分相同时按策略类型编码升序确定性选取,消除 Spring Bean 注入顺序
 * 对主策略选取的隐式依赖。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
public class BuyStrategyMatcher {

    private final List<StockBuyStrategy> buyStrategies;

    /**
     * 构造策略匹配器。
     *
     * @param buyStrategies 全部买入策略(Spring按Bean顺序注入)
     */
    public BuyStrategyMatcher(List<StockBuyStrategy> buyStrategies) {
        this.buyStrategies = buyStrategies == null ? List.of() : buyStrategies;
    }

    /**
     * 遍历全部买入策略,选取主策略并汇总命中策略与最优质量分。
     *
     * @param context 买入上下文
     * @return 策略匹配结果,无命中时 primaryStrategy 为 null、bestScore 为 null
     */
    public StrategyMatchResult match(BuyContext context) {
        List<StockBuyStrategy> matchedStrategies = new ArrayList<>();
        StockBuyStrategy primaryStrategy = null;
        BigDecimal bestScore = null;

        for (StockBuyStrategy strategy : buyStrategies) {
            if (!isStrategyMatched(strategy, context)) {
                continue;
            }
            matchedStrategies.add(strategy);
            BigDecimal score = strategy.calculateQualityScore(context);
            if (shouldReplacePrimary(primaryStrategy, bestScore, strategy, score)) {
                primaryStrategy = strategy;
                bestScore = score;
            }
        }
        return new StrategyMatchResult(primaryStrategy, matchedStrategies, bestScore);
    }

    /**
     * 判断策略是否适配风格且命中买入条件。
     *
     * @param strategy 买入策略
     * @param context  买入上下文
     * @return true表示风格适配且命中
     */
    private boolean isStrategyMatched(StockBuyStrategy strategy, BuyContext context) {
        return strategy.isApplicableStyle(context.stylePrior()) && strategy.matches(context);
    }

    /**
     * 判断候选策略是否替换当前主策略: 质量分更高时替换;
     * 质量分相同且策略类型编码更小时替换(确定性tie-break,避免依赖Bean注入顺序)。
     *
     * @param current        当前主策略;尚未选取时为null
     * @param currentScore   当前最优质量分;尚未选取时为null
     * @param candidate      候选策略
     * @param candidateScore 候选策略质量分
     * @return 需要替换时返回true
     */
    private boolean shouldReplacePrimary(StockBuyStrategy current, BigDecimal currentScore,
                                         StockBuyStrategy candidate, BigDecimal candidateScore) {
        if (current == null) {
            return true;
        }
        int comparison = candidateScore.compareTo(currentScore);
        if (comparison > 0) {
            return true;
        }
        return comparison == 0 && hasLowerStrategyCode(candidate, current);
    }

    /**
     * 判断候选策略类型编码是否按字典序小于当前主策略。
     * <p>
     * 任一策略类型编码为空时不参与tie-break,保持当前主策略不变。
     *
     * @param candidate 候选策略
     * @param current   当前主策略
     * @return 候选策略编码更小时返回true
     */
    private boolean hasLowerStrategyCode(StockBuyStrategy candidate, StockBuyStrategy current) {
        String candidateCode = candidate.getStrategyType() == null ? null : candidate.getStrategyType().getCode();
        String currentCode = current.getStrategyType() == null ? null : current.getStrategyType().getCode();
        return candidateCode != null && currentCode != null && candidateCode.compareTo(currentCode) < 0;
    }

    /**
     * 策略匹配结果。
     *
     * @param primaryStrategy   主策略(质量分最高);无命中时为 null
     * @param matchedStrategies 全部命中策略列表
     * @param bestScore         最优质量分;无命中时为 null
     */
    public record StrategyMatchResult(
            StockBuyStrategy primaryStrategy,
            List<StockBuyStrategy> matchedStrategies,
            BigDecimal bestScore
    ) {
    }
}
