package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySourceManifest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 回放只读输入窗口数据。
 *
 * <p>由 {@link StockReplayInputLoader} 在单一只读 + Repeatable Read 事务内分块加载并索引,
 * 供引擎各轨道共享。全部数据仅存在于内存,不写任何业务表。携带同一快照下的输入来源清单
 * {@link StockReplaySourceManifest},用于固化成功完成标识。</p>
 *
 * @param barsByStock          股票ID → (bar开始时间 → bar),按时间升序
 * @param featuresByStock      股票ID → (bar开始时间 → 策略特征),按时间升序
 * @param monthlyStatesByMonth 生效月份 → (股票ID → 已确认月度状态)
 * @param sourceManifest       本次加载的输入来源清单(同一一致性快照)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayWindowData(
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock,
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock,
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStatesByMonth,
        StockReplaySourceManifest sourceManifest
) {
}
