package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.util.List;

/**
 * 一次回放运行的完整产物集合(内存态,由 {@code StockReplayResultWriter} 落盘)。
 *
 * @param runId        回放运行标识
 * @param summary      运行摘要
 * @param trades       逐笔交易记录
 * @param rejections   拒绝/观察记录
 * @param equityPoints 逐轮净值点
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayResult(
        String runId,
        StockReplaySummary summary,
        List<StockReplayTrade> trades,
        List<StockReplayRejection> rejections,
        List<StockReplayEquityPoint> equityPoints
) {
}
