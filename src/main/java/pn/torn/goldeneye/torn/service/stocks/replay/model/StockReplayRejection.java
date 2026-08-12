package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回放拒绝/观察记录(输出到 rejections.csv)。
 *
 * <p>覆盖拒绝观察理论路径、高风险观察与原始BUY对照三类研究事实。</p>
 *
 * @param runId                    回放运行标识
 * @param track                    轨道编码
 * @param roundTime                信号轮次时间
 * @param stocksId                 股票ID
 * @param stocksShortname          股票简称
 * @param strategyType             主策略编码
 * @param qualityScore             主策略质量分
 * @param monthlyStyle             冻结策略适配风格
 * @param riskLevel                冻结风险等级
 * @param eligibilityResult        资格结果(ALLOWED/REJECTED)
 * @param eligibilityReasons       资格拒绝原因编码
 * @param candidateRank            候选排名(未参与排名为null)
 * @param portfolioDecision        组合决策(FORMAL/SHADOW/REJECTED/OBSERVED)
 * @param rejectReason             拒绝/观察原因编码
 * @param observationResult        理论观察结果编码
 * @param laterMfe                 14天最大有利偏移
 * @param laterMae                 14天最大不利偏移
 * @param theoreticalEntryTime     理论入场时间
 * @param theoreticalEntryPrice    理论入场价格
 * @param theoreticalExitSignalTime 理论退出信号时间
 * @param theoreticalExitTime      理论退出成交时间
 * @param theoreticalExitPrice     理论退出成交价格
 * @param theoreticalCloseType     理论退出关闭类型编码
 * @param theoreticalNetReturn     理论净收益
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayRejection(
        String runId,
        String track,
        LocalDateTime roundTime,
        Integer stocksId,
        String stocksShortname,
        String strategyType,
        BigDecimal qualityScore,
        String monthlyStyle,
        String riskLevel,
        String eligibilityResult,
        String eligibilityReasons,
        Integer candidateRank,
        String portfolioDecision,
        String rejectReason,
        String observationResult,
        BigDecimal laterMfe,
        BigDecimal laterMae,
        LocalDateTime theoreticalEntryTime,
        BigDecimal theoreticalEntryPrice,
        LocalDateTime theoreticalExitSignalTime,
        LocalDateTime theoreticalExitTime,
        BigDecimal theoreticalExitPrice,
        String theoreticalCloseType,
        BigDecimal theoreticalNetReturn
) {
}
