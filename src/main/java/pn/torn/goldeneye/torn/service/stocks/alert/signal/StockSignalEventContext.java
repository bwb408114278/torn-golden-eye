package pn.torn.goldeneye.torn.service.stocks.alert.signal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowTrackRecorder;

/**
 * 信号事件上下文 - 封装创建原始信号事件所需的全部信息。
 * <p>
 * 作为 {@link StockShadowTrackRecorder#recordSignalEvent(StockSignalEventContext)} 的入参,
 * 由调用方在策略匹配与资格评估完成后组装,保证事件记录的字段完整性。
 *
 * @param stocksId             股票ID
 * @param stocksShortname      股票简称快照
 * @param strategyType         策略类型编码
 * @param signalReferencePrice 信号参考价(信号触发时bar的收盘价)
 * @param stylePrior           风格-策略契合度(来自月度状态)
 * @param styleMaturity        风格-成熟度等级(来自月度状态)
 * @param riskLevel            风格-风险等级(来自月度状态)
 * @param styleEffectiveMonth  风格生效月份(来自月度状态)
 * @param buyRuleVersion       买入规则版本
 * @param qualityScore         信号质量评分
 * @param featureSnapshot      特征快照(JSON文本)
 * @param styleSnapshot        风格快照(JSON文本)
 * @param eligibilityResult    资格审查结果编码(ALLOWED/REJECTED/OBSERVED)
 * @param eligibilityReasons   资格审查原因编码列表,可为null
 * @param candidateRank        候选排名,未通过资格审查时为null
 * @param portfolioDecision    组合决策编码(FORMAL/SHADOW/REJECTED)
 * @param rejectReason         拒绝原因编码,portfolioDecision为REJECTED时非空,可为null
 * @param roundTime            信号产生的轮次时间
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
public record StockSignalEventContext(
        Integer stocksId,
        String stocksShortname,
        String strategyType,
        BigDecimal signalReferencePrice,
        String stylePrior,
        String styleMaturity,
        String riskLevel,
        LocalDate styleEffectiveMonth,
        String buyRuleVersion,
        BigDecimal qualityScore,
        String featureSnapshot,
        String styleSnapshot,
        String eligibilityResult,
        List<String> eligibilityReasons,
        Integer candidateRank,
        String portfolioDecision,
        String rejectReason,
        LocalDateTime roundTime
) {
}
