package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 回放只读输入窗口数据。
 *
 * <p>由 {@link StockReplayInputLoader} 按时间窗口分块只读加载并索引,供引擎各轨道共享。
 * 全部数据仅存在于内存,不写任何业务表。</p>
 *
 * @param barsByStock          股票ID → (bar开始时间 → bar),按时间升序
 * @param featuresByStock      股票ID → (bar开始时间 → 策略特征),按时间升序
 * @param monthlyStatesByMonth 生效月份 → (股票ID → 已确认月度状态)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayWindowData(
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock,
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock,
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStatesByMonth
) {
}
