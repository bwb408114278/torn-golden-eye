package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.time.LocalDateTime;

/**
 * 回放净值曲线点。
 *
 * @param runId 运行标识
 * @param track 轨道
 * @param time 时间
 * @param equity 净值
 * @param cash 现金
 * @param invested 投入资金
 * @param status 数据状态
 * @param missingStocks 缺失行情股票
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayEquityPoint(String runId, StockReplayTrackEnum track, LocalDateTime time,
                                     String equity, String cash, String invested,
                                     String status, String missingStocks) {
}
