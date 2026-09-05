package pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * α策略横截面排名纯计算器。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaRankingCalculator {

    /**
     * 按共同有效收盘序列计算α排名。
     *
     * @param closesByStock 股票ID到按时间升序收盘价序列
     * @return 按综合分降序、股票ID升序排列的结果
     * @throws IllegalArgumentException 输入股票数或历史长度不足时抛出
     */
    public static List<StockAlphaRankingResult> calculate(Map<Integer, List<BigDecimal>> closesByStock) {
        validate(closesByStock);
        Map<Integer, Factors> factors = closesByStock.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> calculateFactors(entry.getValue())));
        Map<Integer, BigDecimal> r20Ranks = normalizedRanks(factors, true);
        Map<Integer, BigDecimal> r1Ranks = normalizedRanks(factors, false);
        List<StockAlphaRankingResult> ranked = factors.keySet().stream().map(stockId -> {
            Factors factor = factors.get(stockId);
            BigDecimal score = r20Ranks.get(stockId).multiply(StockAlphaRuleDefinition.R20_WEIGHT)
                    .add(r1Ranks.get(stockId).multiply(StockAlphaRuleDefinition.R1_WEIGHT))
                    .setScale(StockAlphaRuleDefinition.CALC_SCALE, RoundingMode.HALF_UP);
            return new StockAlphaRankingResult(stockId, factor.r20(), factor.r1(), r20Ranks.get(stockId),
                    r1Ranks.get(stockId), score, 0);
        }).sorted(Comparator.comparing(StockAlphaRankingResult::alphaScore).reversed()
                .thenComparing(StockAlphaRankingResult::stocksId)).toList();
        return java.util.stream.IntStream.range(0, ranked.size())
                .mapToObj(index -> {
                    StockAlphaRankingResult result = ranked.get(index);
                    return new StockAlphaRankingResult(result.stocksId(), result.r20(), result.r1(),
                            result.r20Rank(), result.r1Rank(), result.alphaScore(), index + 1);
                }).toList();
    }

    /**
     * 校验股票池和收盘数据完整性。
     *
     * @param closes 股票收盘序列
     * @throws IllegalArgumentException 数据不完整时抛出
     */
    private static void validate(Map<Integer, List<BigDecimal>> closes) {
        if (closes == null || closes.size() != StockAlphaRuleDefinition.MEMBER_COUNT
                || closes.values().stream().anyMatch(values -> values == null || values.size() < 21
                || values.stream().anyMatch(value -> value == null || value.signum() <= 0))) {
            throw new IllegalArgumentException("α股票池或共同有效收盘数据不完整");
        }
    }

    /**
     * 计算单只股票的20日和1日收益因子。
     *
     * @param closes 按时间升序排列的收盘价
     * @return 收益因子
     */
    private static Factors calculateFactors(List<BigDecimal> closes) {
        int last = closes.size() - 1;
        BigDecimal current = closes.get(last);
        return new Factors(current.divide(closes.get(last - 20), StockAlphaRuleDefinition.CALC_SCALE,
                RoundingMode.HALF_UP).subtract(BigDecimal.ONE).setScale(StockAlphaRuleDefinition.CALC_SCALE, RoundingMode.HALF_UP),
                current.divide(closes.get(last - 1), StockAlphaRuleDefinition.CALC_SCALE,
                        RoundingMode.HALF_UP).subtract(BigDecimal.ONE).setScale(StockAlphaRuleDefinition.CALC_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 计算指定收益因子的归一化排名。
     *
     * @param factors 股票收益因子
     * @param r20     是否计算20日收益排名
     * @return 股票ID到归一化排名的映射
     */
    private static Map<Integer, BigDecimal> normalizedRanks(Map<Integer, Factors> factors, boolean r20) {
        List<Map.Entry<Integer, Factors>> sorted = factors.entrySet().stream()
                .sorted(Comparator.comparing(entry -> r20 ? entry.getValue().r20().negate() : entry.getValue().r1(),
                        Comparator.reverseOrder()))
                .toList();
        Map<Integer, BigDecimal> result = new java.util.HashMap<>();
        for (int index = 0; index < sorted.size(); ) {
            int end = index + 1;
            BigDecimal value = r20 ? sorted.get(index).getValue().r20() : sorted.get(index).getValue().r1();
            while (end < sorted.size() && value.compareTo(r20 ? sorted.get(end).getValue().r20() : sorted.get(end).getValue().r1()) == 0) {
                end++;
            }
            BigDecimal midRank = BigDecimal.valueOf(index + 1L + end).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
            BigDecimal normalized = sorted.size() == 1 ? BigDecimal.ONE : BigDecimal.valueOf(sorted.size()).subtract(midRank)
                    .divide(BigDecimal.valueOf(sorted.size() - 1L), 18, RoundingMode.HALF_UP);
            for (int i = index; i < end; i++) result.put(sorted.get(i).getKey(), normalized);
            index = end;
        }
        return result;
    }

    /**
     * 单只股票的收益因子。
     *
     * @param r20 20日收益
     * @param r1  1日收益
     */
    private record Factors(BigDecimal r20, BigDecimal r1) {
    }
}
