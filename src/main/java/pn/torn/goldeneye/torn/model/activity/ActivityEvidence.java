package pn.torn.goldeneye.torn.model.activity;

/**
 * 活跃度证据判定结果
 * <p>
 * 由 {@link pn.torn.goldeneye.torn.service.activity.ActivityEvidenceClassifier} 根据双证据 OR 判定产出，
 * 表达一个采样槽内成员的活跃状态证据。
 *
 * @param statusActive    last_action.status 为 Online 或 Idle
 * @param recentAction    collectedAt - lastAction.timestamp 在 [0, 15分钟) 范围内
 * @param estimatedActive estimatedActive = statusActive OR recentAction
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
public record ActivityEvidence(
        boolean statusActive,
        boolean recentAction,
        boolean estimatedActive) {
}
