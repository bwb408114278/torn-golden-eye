package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * α策略上线前数据就绪门禁。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.06
 */
@Service
@RequiredArgsConstructor
public class StockAlphaReadinessGate {
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final TornStocksDAO stocksDAO;
    private final TornStockAlphaDailySnapshotDAO snapshotDAO;

    /**
     * 判断固定股票池和历史日线预填是否满足正式新入场条件。
     *
     * @return 股票池有效且至少存在60个共同有效自然日时返回true
     */
    public boolean isReady() {
        return hasValidStockUniverse() && hasWarmupSnapshots();
    }

    private boolean hasValidStockUniverse() {
        List<TornStocksDO> stocks = stocksDAO.listByIds(StockAlphaRuleDefinition.stockUniverse());
        if (stocks == null || stocks.size() != StockAlphaRuleDefinition.MEMBER_COUNT) {
            return false;
        }
        Set<Integer> actualIds = new HashSet<>();
        for (TornStocksDO stock : stocks) {
            if (stock == null || stock.getId() == null || !actualIds.add(stock.getId())
                    || !StockAlphaRuleDefinition.stockUniverse().contains(stock.getId())
                    || Integer.valueOf(1).equals(stock.getDeleted())
                    || "TCSE".equalsIgnoreCase(stock.getStocksShortname())) {
                return false;
            }
        }
        return actualIds.size() == StockAlphaRuleDefinition.MEMBER_COUNT;
    }

    private boolean hasWarmupSnapshots() {
        List<LocalDate> commonDates = snapshotDAO.selectCommonValidDates(
                StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION,
                StockAlphaRuleDefinition.MEMBER_COUNT,
                MIN_DATE,
                LocalDate.now());
        return commonDates != null && commonDates.size() >= StockAlphaRuleDefinition.WARMUP_COMMON_DAYS;
    }
}
