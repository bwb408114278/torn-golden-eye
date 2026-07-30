package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.time.LocalDateTime;

/**
 * 回放拒绝审计记录。
 *
 * @param runId             运行标识
 * @param track             轨道
 * @param stocksId          股票ID
 * @param strategy          策略
 * @param roundTime         轮次时间
 * @param rejectReason      拒绝原因
 * @param observationResult 拒绝观察结果
 * @param laterMfe          后续MFE
 * @param laterMae          后续MAE
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayRejection(String runId, StockReplayTrackEnum track, Integer stocksId,
                                   String strategy, LocalDateTime roundTime, String rejectReason,
                                   String observationResult, String laterMfe, String laterMae) {
}
