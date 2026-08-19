package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * 一次刷新求解的匿名搜索遥测。只记录搜索规模与预算命中计数，
 * 不包含成员、岗位、内部排程或奖励明细。
 *
 * @param combinationEvaluations 组合评估尝试次数
 * @param budgetTruncations      搜索预算截断的模拟次数
 * @param alternativesCapHits    替代候选上限命中的模拟次数
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.17
 */
public record OcSearchTelemetry(
        int combinationEvaluations,
        int budgetTruncations,
        int alternativesCapHits) {
    public OcSearchTelemetry {
        combinationEvaluations = Math.max(0, combinationEvaluations);
        budgetTruncations = Math.max(0, budgetTruncations);
        alternativesCapHits = Math.max(0, alternativesCapHits);
    }

    /**
     * 构造未参与求解的空遥测。
     *
     * @return 全零遥测
     */
    public static OcSearchTelemetry empty() {
        return new OcSearchTelemetry(0, 0, 0);
    }
}
