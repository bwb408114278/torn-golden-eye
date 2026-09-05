package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * α策略历史日线预填只读验证服务测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@ExtendWith(MockitoExtension.class)
class StockAlphaPrefillValidationServiceTest {
    private static final LocalDate END_DATE = LocalDate.of(2026, 9, 5);

    @Mock
    private TornStockMarketBar15mDAO barDao;

    @Test
    void validatesSixtyCommonDaysAndVersionsWithoutWritingFacts() {
        when(barDao.selectByStocksAndTimeRange(eq(StockAlphaRuleDefinition.stockUniverse()),
                eq(END_DATE.minusDays(80).atStartOfDay()), eq(END_DATE.plusDays(1).atStartOfDay()), eq("1.2.0")))
                .thenReturn(bars(60));

        StockAlphaPrefillValidationService.PrefillValidationReport report =
                new StockAlphaPrefillValidationService(barDao).validate(END_DATE);

        assertEquals(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION, report.stockUniverseVersion());
        assertEquals(StockAlphaRuleDefinition.RULE_VERSION, report.alphaRuleVersion());
        assertEquals(35, report.memberCount());
        assertEquals(60, report.commonDayCount());
        assertTrue(report.warmupComplete());
        assertTrue(report.rankingComplete());
        assertEquals(35, report.latestRankingStocksId().size());
        verify(barDao).selectByStocksAndTimeRange(eq(StockAlphaRuleDefinition.stockUniverse()),
                eq(END_DATE.minusDays(80).atStartOfDay()), eq(END_DATE.plusDays(1).atStartOfDay()), eq("1.2.0"));
        verifyNoMoreInteractions(barDao);
    }

    @Test
    void repeatedValidationAndInputOrderProduceSameDigest() {
        List<TornStockMarketBar15mDO> bars = bars(60);
        List<TornStockMarketBar15mDO> shuffled = new ArrayList<>(bars);
        Collections.reverse(shuffled);
        when(barDao.selectByStocksAndTimeRange(eq(StockAlphaRuleDefinition.stockUniverse()),
                eq(END_DATE.minusDays(80).atStartOfDay()), eq(END_DATE.plusDays(1).atStartOfDay()), eq("1.2.0")))
                .thenReturn(bars, shuffled);

        StockAlphaPrefillValidationService service = new StockAlphaPrefillValidationService(barDao);
        StockAlphaPrefillValidationService.PrefillValidationReport first = service.validate(END_DATE);
        StockAlphaPrefillValidationService.PrefillValidationReport second = service.validate(END_DATE);

        assertEquals(first, second);
        assertEquals(first.sourceDigest(), second.sourceDigest());
    }

    @Test
    void incompleteUniverseDoesNotPassWarmup() {
        when(barDao.selectByStocksAndTimeRange(eq(StockAlphaRuleDefinition.stockUniverse()),
                eq(END_DATE.minusDays(80).atStartOfDay()), eq(END_DATE.plusDays(1).atStartOfDay()), eq("1.2.0")))
                .thenReturn(bars(59));

        StockAlphaPrefillValidationService.PrefillValidationReport report =
                new StockAlphaPrefillValidationService(barDao).validate(END_DATE);

        assertEquals(59, report.commonDayCount());
        assertFalse(report.warmupComplete());
        assertTrue(report.rankingComplete());
    }

    private List<TornStockMarketBar15mDO> bars(int days) {
        List<TornStockMarketBar15mDO> bars = new ArrayList<>();
        LocalDate start = END_DATE.minusDays(days - 1L);
        long id = 1L;
        for (int day = 0; day < days; day++) {
            LocalDate date = start.plusDays(day);
            for (int stockId = 1; stockId <= 35; stockId++) {
                TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
                bar.setId(id++);
                bar.setStocksId(stockId);
                bar.setBarStartTime(date.atTime(23, 45));
                bar.setBarEndTime(date.plusDays(1).atStartOfDay());
                bar.setUsable(true);
                bar.setSampleCount(15);
                bar.setLastSampleTime(date.atTime(23, 59));
                bar.setLastPrice(BigDecimal.valueOf(100L + stockId + day));
                bars.add(bar);
            }
        }
        return bars;
    }
}
