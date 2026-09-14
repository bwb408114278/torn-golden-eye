package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHashUtils;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

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
    private final StockMarketClock marketClock;

    /**
     * 判断固定股票池和历史日线预填是否满足正式新入场条件。
     *
     * @return 股票池有效且至少存在60个共同有效自然日时返回true
     */
    public boolean isReady() {
        return hasValidStockUniverse() && hasWarmupSnapshots();
    }

    /**
     * 判断固定股票池的定义元数据和数据库成员是否有效。
     *
     * @return 股票池摘要匹配、生效时间已到且数据库成员完整有效时返回true
     */
    private boolean hasValidStockUniverse() {
        if (!isStockUniverseMetadataValid()) {
            return false;
        }
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

    /**
     * 校验股票池摘要和生效时间，确保运行时使用已发布的固定成员定义。
     *
     * @return 股票池元数据有效时返回true
     */
    private boolean isStockUniverseMetadataValid() {
        String canonicalUniverse = String.join(",", StockAlphaRuleDefinition.stockUniverse().stream()
                .map(String::valueOf)
                .toList());
        return StockAlphaRuleDefinition.STOCK_UNIVERSE_DIGEST.equals(StockHashUtils.sha256(canonicalUniverse))
                && !marketClock.now().isBefore(StockAlphaRuleDefinition.STOCK_UNIVERSE_EFFECTIVE_AT);
    }

    /**
     * 判断是否存在满足预热要求的共同有效日线快照。
     * <p>
     * 统计上界固定为最近已结束自然日:未结束自然日的快照仍可能变化且不得参与统计,
     * 否则共同有效日会提前增加并让phase提前推进。
     *
     * @return 已结束自然日中的共同有效日数量达到预热要求时返回true
     */
    private boolean hasWarmupSnapshots() {
        List<LocalDate> commonDates = snapshotDAO.selectCommonValidDates(
                StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION,
                StockAlphaRuleDefinition.stockUniverse(),
                MIN_DATE,
                marketClock.lastEndedNaturalDay());
        return commonDates != null && commonDates.size() >= StockAlphaRuleDefinition.WARMUP_COMMON_DAYS;
    }
}
