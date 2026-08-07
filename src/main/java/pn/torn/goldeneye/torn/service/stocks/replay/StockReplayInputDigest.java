package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySourceManifest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeSet;

/**
 * 回放输入内容确定性摘要计算器。
 *
 * <p>对每类实际回放输入(bar/feature/月度状态)按稳定顺序做流式SHA-256,保证同一时间边界、
 * 行数与版本下,只要任一行的实际内容(如bar lastPrice、feature值、月度风格/风险)被修改,
 * 内容摘要必然变化。该摘要纳入 {@link StockReplaySourceManifest#contentSha256()},进而改变
 * 完成标识 hash,避免"同边界不同内容"被误判为同一次成功结果。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
final class StockReplayInputDigest {

    private static final String SEPARATOR = "\u0001";

    private StockReplayInputDigest() {
    }

    /**
     * 按稳定顺序对全部实际输入内容计算流式SHA-256摘要。
     *
     * @param barsByStock          股票ID → (bar开始时间 → bar)
     * @param featuresByStock      股票ID → (bar开始时间 → 特征)
     * @param monthlyStatesByMonth 生效月份 → (股票ID → 月度状态)
     * @return 64位十六进制SHA-256摘要
     */
    static String compute(
            Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock,
            Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock,
            Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStatesByMonth) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendBars(digest, barsByStock);
            appendFeatures(digest, featuresByStock);
            appendMonthlyStates(digest, monthlyStatesByMonth);
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    /**
     * 将全部bar内容按稳定顺序写入摘要。
     *
     * <p>按股票ID升序、bar开始时间升序遍历,逐bar追加股票ID、bar开始时间、lastPrice、usable、
     * 构建版本与来源最大历史ID,保证同一批数据必然产生相同摘要。</p>
     *
     * @param digest      正在累积的SHA-256摘要器
     * @param barsByStock 股票ID → (bar开始时间 → bar);为空时直接返回
     */
    private static void appendBars(MessageDigest digest,
                                   Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock) {
        if (barsByStock == null) {
            return;
        }
        for (Integer stocksId : new TreeSet<>(barsByStock.keySet())) {
            NavigableMap<LocalDateTime, TornStockMarketBar15mDO> bars = barsByStock.get(stocksId);
            if (bars == null) {
                continue;
            }
            for (TornStockMarketBar15mDO bar : bars.values()) {
                append(digest, stocksId);
                append(digest, bar.getBarStartTime());
                append(digest, bar.getLastPrice());
                append(digest, bar.getUsable());
                append(digest, bar.getBuildVersion());
                append(digest, bar.getSourceMaxHistoryId());
            }
        }
    }

    /**
     * 将全部策略特征内容按稳定顺序写入摘要。
     *
     * <p>按股票ID升序、bar开始时间升序遍历,逐特征追加股票ID、bar开始时间及全部特征字段与
     * 特征版本,保证同一批特征数据必然产生相同摘要。</p>
     *
     * @param digest          正在累积的SHA-256摘要器
     * @param featuresByStock 股票ID → (bar开始时间 → 特征);为空时直接返回
     */
    private static void appendFeatures(MessageDigest digest,
                                       Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock) {
        if (featuresByStock == null) {
            return;
        }
        for (Integer stocksId : new TreeSet<>(featuresByStock.keySet())) {
            NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO> features = featuresByStock.get(stocksId);
            if (features == null) {
                continue;
            }
            for (TornStockStrategyFeature15mDO feature : features.values()) {
                append(digest, stocksId);
                append(digest, feature.getBarStartTime());
                append(digest, feature.getReferencePrice());
                append(digest, feature.getMa1d());
                append(digest, feature.getMa7d());
                append(digest, feature.getMa30d());
                append(digest, feature.getZscore1d());
                append(digest, feature.getZscore7d());
                append(digest, feature.getZscore30d());
                append(digest, feature.getReturn6h());
                append(digest, feature.getReturn1d());
                append(digest, feature.getReturn7d());
                append(digest, feature.getReturn14d());
                append(digest, feature.getLow30d());
                append(digest, feature.getHigh30d());
                append(digest, feature.getWidth30d());
                append(digest, feature.getPosition30());
                append(digest, feature.getPctAbove30dLow());
                append(digest, feature.getPctBelow30dHigh());
                append(digest, feature.getStrategyReady());
                append(digest, feature.getFeatureVersion());
            }
        }
    }

    /**
     * 将全部月度状态内容按稳定顺序写入摘要。
     *
     * <p>按生效月份升序、股票ID升序遍历,逐状态追加股票ID、生效月份、月度决策字段
     * (策略适配度/成熟度/风险等级/建议性格/手动覆盖)与决策规则版本。</p>
     *
     * @param digest               正在累积的SHA-256摘要器
     * @param monthlyStatesByMonth 生效月份 → (股票ID → 月度状态);为空时直接返回
     */
    private static void appendMonthlyStates(MessageDigest digest,
                                            Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStatesByMonth) {
        if (monthlyStatesByMonth == null) {
            return;
        }
        for (LocalDate month : new TreeSet<>(monthlyStatesByMonth.keySet())) {
            Map<Integer, TornStockMonthlyStateDO> byStock = monthlyStatesByMonth.get(month);
            if (byStock == null) {
                continue;
            }
            for (Integer stocksId : new TreeSet<>(byStock.keySet())) {
                TornStockMonthlyStateDO state = byStock.get(stocksId);
                if (state == null) {
                    continue;
                }
                append(digest, stocksId);
                append(digest, month);
                append(digest, state.getStrategyFitPrior());
                append(digest, state.getMaturity());
                append(digest, state.getRiskLevel());
                append(digest, state.getSuggestedPersonality());
                append(digest, state.getManualOverride());
                append(digest, state.getPersonalityRuleVersion());
                append(digest, state.getRiskRuleVersion());
            }
        }
    }

    /**
     * 将一个字段值写入摘要。
     *
     * <p>空值以空串写入,字段间以{@link #SEPARATOR}分隔,避免相邻字段值拼接产生歧义。</p>
     *
     * @param digest 正在累积的SHA-256摘要器
     * @param value  待写入的字段值;为空时写空串
     */
    private static void append(MessageDigest digest, Object value) {
        String token = value == null ? "" : String.valueOf(value);
        digest.update(token.getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR.getBytes(StandardCharsets.UTF_8));
    }
}
