package pn.torn.goldeneye.torn.service.stocks.alert.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockRollingRsiWindow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 指令触发时分钟RSI(60)现算组件单元测试。
 * <p>
 * 覆盖口径一致性(设计§3.6): 61点序列与直接使用{@link StockRollingRsiWindow}一致、
 * 不足60期返回50、lossSum≈0返回100、同一自然分钟重复采样保留最大id(与bar构建去重同口径)。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("分钟RSI现算组件测试")
class StockMinuteRsiCalculatorTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 4, 10, 0);
    private static final int STOCKS_ID = 1;

    @Mock
    private TornStocksHistoryDAO tornStocksHistoryDao;

    @InjectMocks
    private StockMinuteRsiCalculator calculator;

    @Test
    @DisplayName("61点单调上涨序列_结果与直接使用StockRollingRsiWindow一致")
    void computeAll_sixtyOnePoints_matchesRollingWindowDirectly() {
        List<StockPricePoint> points = ascendingPoints(61, BigDecimal.ONE);
        when(tornStocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(points);

        Map<Integer, BigDecimal> result = calculator.computeAll(BASE);

        StockRollingRsiWindow expected = new StockRollingRsiWindow();
        for (StockPricePoint point : points) {
            expected.add(point.price());
        }
        assertEquals(0, expected.rsi().compareTo(result.get(STOCKS_ID)),
                "现算RSI必须与直接使用StockRollingRsiWindow完全一致");
        assertEquals(BigDecimal.valueOf(100), result.get(STOCKS_ID),
                "全部上涨lossSum≈0时应返回100");
    }

    @Test
    @DisplayName("不足61点_返回50(与滚动窗口不足60期口径一致)")
    void computeAll_insufficientPoints_returns50() {
        when(tornStocksHistoryDao.selectHistoryPointsRange(any(), any()))
                .thenReturn(ascendingPoints(30, BigDecimal.ONE));

        Map<Integer, BigDecimal> result = calculator.computeAll(BASE);

        assertEquals(0, BigDecimal.valueOf(50).compareTo(result.get(STOCKS_ID)),
                "不足60期必须返回50");
    }

    @Test
    @DisplayName("涨跌混合序列_结果与直接使用StockRollingRsiWindow一致")
    void computeAll_mixedSequence_matchesRollingWindowDirectly() {
        List<StockPricePoint> points = mixedPoints(61);
        when(tornStocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(points);

        Map<Integer, BigDecimal> result = calculator.computeAll(BASE);

        StockRollingRsiWindow expected = new StockRollingRsiWindow();
        for (StockPricePoint point : points) {
            expected.add(point.price());
        }
        assertEquals(0, expected.rsi().compareTo(result.get(STOCKS_ID)),
                "涨跌混合序列必须与直接使用StockRollingRsiWindow完全一致");
    }

    @Test
    @DisplayName("同一自然分钟重复采样_保留最大id记录(与bar构建dedupByTime同口径)")
    void computeAll_duplicateMinute_keepsMaxIdRecord() {
        LocalDateTime duplicateTime = BASE.minusMinutes(61);
        StockPricePoint smallId = new StockPricePoint(1L, STOCKS_ID, "TCS",
                BigDecimal.valueOf(100), 0, duplicateTime);
        StockPricePoint largeId = new StockPricePoint(2L, STOCKS_ID, "TCS",
                BigDecimal.valueOf(500), 0, duplicateTime);
        List<StockPricePoint> points = new java.util.ArrayList<>();
        points.add(smallId);
        points.add(largeId);
        points.addAll(ascendingPointsFrom(60, BigDecimal.valueOf(500), duplicateTime.plusMinutes(1)));

        when(tornStocksHistoryDao.selectHistoryPointsRange(any(), any())).thenReturn(points);

        Map<Integer, BigDecimal> result = calculator.computeAll(BASE);

        // 大id(500)生效: 与小id(100)作为前价会产生不同的首个涨跌幅,直接构造期望窗口验证
        StockRollingRsiWindow expected = new StockRollingRsiWindow();
        expected.add(BigDecimal.valueOf(500));
        for (StockPricePoint point : ascendingPointsFrom(60, BigDecimal.valueOf(500),
                duplicateTime.plusMinutes(1))) {
            expected.add(point.price());
        }
        assertEquals(0, expected.rsi().compareTo(result.get(STOCKS_ID)),
                "重复自然分钟必须保留最大id记录,去重口径与bar构建一致");
    }

    /**
     * 构建每分钟递增step的61点序列(按id升序,SQL返回顺序stocks_id,reg_date_time,id)。
     *
     * @param count 点数
     * @param step  每步增量
     * @return 价格点列表(时间升序)
     */
    private static List<StockPricePoint> ascendingPoints(int count, BigDecimal step) {
        return ascendingPointsFrom(count, step, BASE.minusMinutes(count));
    }

    /**
     * 从指定起始时间构建每分钟递增step的点序列。
     *
     * @param count     点数
     * @param step      每步增量
     * @param startFrom 起始时间(含)
     * @return 价格点列表(时间升序)
     */
    private static List<StockPricePoint> ascendingPointsFrom(int count, BigDecimal step,
                                                             LocalDateTime startFrom) {
        java.util.List<StockPricePoint> points = new java.util.ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(1000);
        for (int i = 0; i < count; i++) {
            points.add(new StockPricePoint((long) (i + 1), STOCKS_ID, "TCS",
                    price, 0, startFrom.plusMinutes(i)));
            price = price.add(step);
        }
        return points;
    }

    /**
     * 构建涨跌交替的61点序列(首点仅设前价,之后交替±)。
     *
     * @param count 点数
     * @return 价格点列表
     */
    private static List<StockPricePoint> mixedPoints(int count) {
        java.util.List<StockPricePoint> points = new java.util.ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(1000);
        for (int i = 0; i < count; i++) {
            points.add(new StockPricePoint((long) (i + 1), STOCKS_ID, "TCS",
                    price, 0, BASE.minusMinutes(count).plusMinutes(i)));
            price = i % 2 == 0 ? price.add(BigDecimal.valueOf(3)) : price.subtract(BigDecimal.valueOf(1));
        }
        return points;
    }
}
