package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockRollingRsiWindow;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指令触发时的分钟RSI(60)现算组件。
 * <p>
 * 15m特征表{@code torn_stock_strategy_feature_15m}冻结不加RSI列(加列将导致
 * {@code FEATURE_VERSION}变更与全量特征重建),RSI改为指令触发时基于
 * {@code torn_stocks_history.current_price}现算,口径与旧分钟特征链完全一致:
 * <ul>
 *   <li>复用无状态纯计算类{@link StockRollingRsiWindow}(PERIOD=60,首次add仅设置前价,
 *       需61个点才产生60个涨跌幅);</li>
 *   <li>不足60期返回50、lossSum小于epsilon返回100,阈值无需重新校准;</li>
 *   <li>同一自然分钟的重复采样去重口径与bar构建一致: 复用
 *       {@link Stock15mBarBuildService#dedupByTime(List)}(保留最大id),禁止另立第二种口径;</li>
 *   <li>一次范围查询覆盖全部股票(35支×61≈2135行),不为单股发N+1查询。</li>
 * </ul>
 * 输入SQL按{@code stocks_id, reg_date_time, id}升序返回,组内同一采集时间的后续记录id更大,
 * 与去重语义的前提一致。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@Component
@RequiredArgsConstructor
public class StockMinuteRsiCalculator {

    /**
     * RSI(60)需要的历史分钟点数(60个涨跌幅+1个前价)
     */
    private static final int REQUIRED_POINTS = 61;

    private final TornStocksHistoryDAO tornStocksHistoryDao;

    /**
     * 批量现算全部股票的RSI(60)。
     *
     * @param analysisTime 分析时点(窗口上界,含)
     * @return 股票ID到RSI的映射;无分钟数据的股票不在映射中,由调用方决定兜底语义
     */
    public Map<Integer, BigDecimal> computeAll(LocalDateTime analysisTime) {
        LocalDateTime since = analysisTime.minusMinutes(REQUIRED_POINTS);
        LocalDateTime endTime = analysisTime.plusNanos(1);
        List<StockPricePoint> points = tornStocksHistoryDao.selectHistoryPointsRange(since, endTime);
        if (CollectionUtils.isEmpty(points)) {
            return Map.of();
        }

        Map<Integer, List<StockPricePoint>> byStock = new LinkedHashMap<>();
        for (StockPricePoint point : points) {
            if (point == null || point.stocksId() == null || point.time() == null || point.price() == null) {
                continue;
            }
            byStock.computeIfAbsent(point.stocksId(), key -> new ArrayList<>()).add(point);
        }

        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<StockPricePoint>> entry : byStock.entrySet()) {
            List<StockPricePoint> uniquePoints =
                    Stock15mBarBuildService.dedupByTime(entry.getValue()).uniquePoints();
            int fromIndex = Math.max(0, uniquePoints.size() - REQUIRED_POINTS);
            StockRollingRsiWindow window = new StockRollingRsiWindow();
            for (StockPricePoint point : uniquePoints.subList(fromIndex, uniquePoints.size())) {
                window.add(point.price());
            }
            result.put(entry.getKey(), window.rsi());
        }
        return result;
    }
}
