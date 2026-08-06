package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回放逐轮净值点(输出到 equity-curve.csv)。
 *
 * <p>权益口径: cashAndReserved + Σ(开放持仓市值 × 0.999 卖出费),与正式日报一致。
 * 影子轨道无现金,权益为持仓市值扣费合计;观察轨道不输出净值点。</p>
 *
 * @param runId           回放运行标识
 * @param track           轨道编码
 * @param roundTime       轮次时间
 * @param equity          总权益(口径见类注释;不可评估时为null)
 * @param cashAndReserved 现金与预留合计
 * @param openPositions   开放持仓数量
 * @param realizedReturn  已实现净收益合计
 * @param utilization     槽位占用率(0~1,非正式轨道为null)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayEquityPoint(
        String runId,
        String track,
        LocalDateTime roundTime,
        BigDecimal equity,
        BigDecimal cashAndReserved,
        int openPositions,
        BigDecimal realizedReturn,
        BigDecimal utilization
) {
}
