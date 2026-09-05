package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * α策略历史日线预填只读验证服务。
 *
 * <p>本服务只读取15分钟bar并在内存中计算验证报告，不写入快照、决策或任何交易事实。</p>
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Service
@RequiredArgsConstructor
public class StockAlphaPrefillValidationService {
    private static final String BAR_BUILD_VERSION = "1.2.0";

    private final TornStockMarketBar15mDAO barDao;

    /**
     * 读取截止日期前的15分钟bar并验证α策略60日预填条件。
     *
     * @param endDate 预填截止自然日
     * @return 只读预填验证报告
     */
    public PrefillValidationReport validate(LocalDate endDate) {
        Objects.requireNonNull(endDate, "预填截止日期不能为空");
        LocalDate startDate = endDate.minusDays(StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + 20L);
        List<TornStockMarketBar15mDO> bars = barDao.selectByStocksAndTimeRange(
                StockAlphaRuleDefinition.stockUniverse(), startDate.atStartOfDay(),
                endDate.plusDays(1).atStartOfDay(), BAR_BUILD_VERSION);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily = calculateDailyCloses(bars);
        List<LocalDate> commonDates = findCommonDates(daily);
        List<StockAlphaRankingResult> rankings = calculateLatestRankings(commonDates, daily);
        return new PrefillValidationReport(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION, StockAlphaRuleDefinition.MEMBER_COUNT,
                StockAlphaRuleDefinition.WARMUP_COMMON_DAYS, commonDates.size(),
                commonDates.size() >= StockAlphaRuleDefinition.WARMUP_COMMON_DAYS,
                rankings.size() == StockAlphaRuleDefinition.MEMBER_COUNT, List.copyOf(commonDates),
                rankings.stream().map(StockAlphaRankingResult::stocksId).toList(),
                digest(commonDates, daily, rankings));
    }

    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> calculateDailyCloses(
            List<TornStockMarketBar15mDO> bars) {
        if (bars == null) {
            return Map.of();
        }
        Map<LocalDate, Map<Integer, List<TornStockMarketBar15mDO>>> grouped = bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> StockAlphaRuleDefinition.stockUniverse().contains(bar.getStocksId()))
                .filter(bar -> bar.getBarStartTime() != null)
                .collect(Collectors.groupingBy(bar -> bar.getBarStartTime().toLocalDate(),
                        TreeMap::new, Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId, TreeMap::new,
                                Collectors.toList())));
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> result = new TreeMap<>();
        grouped.forEach((date, stocks) -> {
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> daily = new TreeMap<>();
            stocks.forEach((stocksId, stockBars) -> daily.put(stocksId,
                    StockAlphaDailyCloseCalculator.calculate(date, stockBars)));
            result.put(date, daily);
        });
        return result;
    }

    private List<LocalDate> findCommonDates(
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily) {
        return daily.entrySet().stream()
                .filter(entry -> entry.getValue().size() == StockAlphaRuleDefinition.MEMBER_COUNT
                        && entry.getValue().keySet().equals(SetHolder.STOCK_UNIVERSE)
                        && entry.getValue().values().stream().allMatch(Objects::nonNull))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private List<StockAlphaRankingResult> calculateLatestRankings(List<LocalDate> commonDates,
                                                                  Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily) {
        if (commonDates.size() < 21) {
            return List.of();
        }
        Map<Integer, List<BigDecimal>> closes = new TreeMap<>();
        commonDates.forEach(date -> daily.get(date).forEach((stocksId, close) ->
                closes.computeIfAbsent(stocksId, ignored -> new ArrayList<>()).add(close.closePrice())));
        return StockAlphaRankingCalculator.calculate(closes);
    }

    private String digest(List<LocalDate> commonDates,
                          Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily,
                          List<StockAlphaRankingResult> rankings) {
        String source = commonDates.stream().map(date -> date + ":" + daily.get(date).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue().closePrice())
                .collect(Collectors.joining(","))).collect(Collectors.joining("|"));
        String ranking = rankings.stream().map(result -> result.stocksId() + "=" + result.alphaScore() + "#" + result.rankPosition())
                .collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((source + "||" + ranking).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算预填验证摘要", exception);
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<Integer> STOCK_UNIVERSE =
                java.util.Set.copyOf(StockAlphaRuleDefinition.stockUniverse());

        private SetHolder() {
        }
    }

    /**
     * α策略预填验证报告。
     *
     * @param stockUniverseVersion  股票池版本
     * @param alphaRuleVersion      α规则版本
     * @param memberCount           固定股票池成员数量
     * @param requiredCommonDays    要求的共同有效日数量
     * @param commonDayCount        实际共同有效日数量
     * @param warmupComplete        是否完成60日预热
     * @param rankingComplete       是否能对固定股票池完成排名
     * @param commonDates           共同有效日期
     * @param latestRankingStocksId 最新排名顺序
     * @param sourceDigest          输入与计算结果摘要
     */
    public record PrefillValidationReport(
            String stockUniverseVersion,
            String alphaRuleVersion,
            int memberCount,
            int requiredCommonDays,
            int commonDayCount,
            boolean warmupComplete,
            boolean rankingComplete,
            List<LocalDate> commonDates,
            List<Integer> latestRankingStocksId,
            String sourceDigest) {
    }
}
