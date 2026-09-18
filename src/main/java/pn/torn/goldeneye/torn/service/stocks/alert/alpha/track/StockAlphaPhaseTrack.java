package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;

/**
 * α相位轨道 - 一条独立决策节奏与其归属槽位的值对象。
 * <p>
 * 相位语义(决策日判定与phase编号)只在本类型实现:生产类不得再出现{@code %5}、
 * 相位偏移字面量或自行判断"是否决策日"。同一轨道编码是决策唯一键
 * {@code (phase_track_code, decision_business_date, phase)} 的第一分量。
 *
 * @param trackCode     轨道编码,决策归属的唯一标识
 * @param portfolioCode 轨道归属组合编码
 * @param slotNo        轨道归属槽位序号(组合内唯一)
 * @param phaseOffset   相位偏移(0..决策间隔-1)
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
public record StockAlphaPhaseTrack(
        String trackCode,
        String portfolioCode,
        int slotNo,
        int phaseOffset) {

    /**
     * 判断给定共同有效日数量是否为本轨道的决策日。
     * <p>
     * 必须显式排除未达到预热的数量:仅用取模判断会让小于预热值的数量因负数取模而被误判为决策日。
     *
     * @param commonDayCount 共同有效日数量
     * @return 达到预热要求且偏移命中本轨道时返回true
     */
    public boolean isDecisionDay(int commonDayCount) {
        return commonDayCount >= StockAlphaRuleDefinition.WARMUP_COMMON_DAYS
                && (commonDayCount - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS)
                % StockAlphaRuleDefinition.DECISION_INTERVAL_DAYS == phaseOffset;
    }

    /**
     * 计算本轨道的消费阶段编号。
     * <p>
     * 仅在 {@link #isDecisionDay(int)} 为true时调用:偏移不同的轨道在同一天至多只有一条命中,
     * 因此同一共同有效日不会产生两个相同(轨道, phase)的决策。
     *
     * @param commonDayCount 共同有效日数量
     * @return phase编号
     */
    public int phaseOf(int commonDayCount) {
        return (commonDayCount - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS - phaseOffset)
                / StockAlphaRuleDefinition.DECISION_INTERVAL_DAYS;
    }

    /**
     * 判断批次是否归属于本轨道。
     * <p>
     * 轨道归属必须同时匹配组合编码与槽位序号:同一组合下的多条轨道共用组合编码,
     * 只比组合会把其它轨道的批次误判为本轨道批次,导致空槽永远不再入场或取到错误持仓。
     * 本方法是批次归属的唯一判定宿主,业务类不得自行拼装比较条件。
     *
     * @param batch 待判断批次;为空时返回false
     * @return 组合编码与槽位序号均与本轨道一致时返回true
     */
    public boolean owns(TornStockVirtualBatchDO batch) {
        return batch != null
                && portfolioCode.equals(batch.getPortfolioCode())
                && Integer.valueOf(slotNo).equals(batch.getSlotNo());
    }
}
