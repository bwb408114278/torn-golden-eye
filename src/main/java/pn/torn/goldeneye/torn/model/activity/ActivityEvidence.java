package pn.torn.goldeneye.torn.model.activity;

/**
 * 活跃度V3互斥证据判定结果
 * <p>
 * 由 {@link pn.torn.goldeneye.torn.service.activity.ActivityEvidenceClassifier} 按单个成员、
 * 单个15分钟采样槽产出。有效活跃与idle-only互斥：{@code Idle + recentAction}归入有效活跃，
 * 不重复记入idle；全部证据为false时该槽仅为已观测的静默状态。
 *
 * @param onlineActive    last_action.status 为 Online（忽略大小写和首尾空白）
 * @param recentAction    collectedAt - lastAction.timestamp 在 [0, 15分钟) 范围内
 * @param idleOnly        last_action.status 为 Idle 且无近期动作
 * @param effectiveActive 有效活跃 = onlineActive OR recentAction
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.21
 */
public record ActivityEvidence(
        boolean onlineActive,
        boolean recentAction,
        boolean idleOnly,
        boolean effectiveActive) {
}
