package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.time.LocalDateTime;

/**
 * 回放交易审计记录。
 *
 * @param runId       运行标识
 * @param track       轨道
 * @param stocksId    股票ID
 * @param strategy    策略
 * @param entryTime   入场时间
 * @param exitTime    出场时间
 * @param entryPrice  入场价
 * @param exitPrice   出场价
 * @param quantity    股数
 * @param grossReturn 毛收益率
 * @param netReturn   净收益率
 * @param closeType   关闭类型
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayTrade(String runId, StockReplayTrackEnum track, Integer stocksId,
                               String strategy, LocalDateTime entryTime, LocalDateTime exitTime,
                               String entryPrice, String exitPrice, long quantity,
                               String grossReturn, String netReturn, String closeType) {
}
