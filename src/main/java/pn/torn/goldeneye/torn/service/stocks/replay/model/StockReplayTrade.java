package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回放逐笔交易记录(输出到 trades.csv)。
 *
 * @param runId           回放运行标识
 * @param track           轨道编码
 * @param roundTime       决策/成交轮次时间
 * @param stocksId        股票ID
 * @param stocksShortname 股票简称
 * @param side            交易方向: BUY / SELL
 * @param strategyType    主策略编码
 * @param signalTime      信号产生轮次时间
 * @param entryTime       理论/实际入场时间
 * @param exitTime        退出成交时间(未平仓为null)
 * @param quantity        整数股数
 * @param entryPrice      入场参考价
 * @param exitPrice       退出成交价(未平仓为null)
 * @param investedCash    投入金额
 * @param sellProceeds    卖出回笼金额(未平仓为null)
 * @param netReturn       扣0.1%卖出费后的净收益率(未平仓为null)
 * @param closeType       关闭类型编码(未平仓为null)
 * @param reasonCode      冻结原因编码(未平仓为null)
 * @param batchNo         批次编号
 * @param holdHours       持有小时数(未平仓为null)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayTrade(
        String runId,
        String track,
        LocalDateTime roundTime,
        Integer stocksId,
        String stocksShortname,
        String side,
        String strategyType,
        LocalDateTime signalTime,
        LocalDateTime entryTime,
        LocalDateTime exitTime,
        Long quantity,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal investedCash,
        BigDecimal sellProceeds,
        BigDecimal netReturn,
        String closeType,
        String reasonCode,
        String batchNo,
        BigDecimal holdHours
) {
}
