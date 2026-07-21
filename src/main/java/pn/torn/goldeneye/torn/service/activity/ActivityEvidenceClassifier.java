package pn.torn.goldeneye.torn.service.activity;

import pn.torn.goldeneye.torn.model.activity.ActivityEvidence;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;

/**
 * 活跃度双证据 OR 判定纯函数
 * <p>
 * 不使用单一 status 或单一 timestamp，而是将两者作为 OR 证据：
 * <pre>
 * statusActive = status 为 Online 或 Idle
 * recentAction = 0 &lt;= collectedAt - lastAction.Timestamp &lt; 15分钟
 * estimatedActive = statusActive OR recentAction
 * </pre>
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
public final class ActivityEvidenceClassifier {

    /** 采集周期（分钟），同时作为最近动作证据窗口 */
    static final int POLL_INTERVAL_MINUTES = 15;

    /** 最近动作证据窗口（秒） */
    static final long RECENT_ACTION_WINDOW_SECONDS = POLL_INTERVAL_MINUTES * 60L;

    private ActivityEvidenceClassifier() {
    }

    /**
     * 根据成员的 last_action 信息和采集时间，判定活跃状态证据
     *
     * @param lastAction          成员的 last_action 信息，可为 null
     * @param collectedAtEpochSecond 采集时刻的 epoch 秒
     * @return 活跃度证据判定结果
     */
    public static ActivityEvidence classifyActivity(
            TornUserLastActionVO lastAction,
            long collectedAtEpochSecond) {
        if (lastAction == null) {
            return new ActivityEvidence(false, false, false);
        }

        boolean statusActive = isStatusActive(lastAction.getStatus());
        boolean recentAction = isRecentAction(lastAction.getTimestamp(), collectedAtEpochSecond);
        boolean estimatedActive = statusActive || recentAction;
        return new ActivityEvidence(statusActive, recentAction, estimatedActive);
    }

    /**
     * 判断 last_action.status 是否为活跃在线状态（Online 或 Idle）
     *
     * @param status last_action.status 原始值
     * @return true 表示 Online 或 Idle
     */
    static boolean isStatusActive(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String trimmed = status.trim();
        return "Online".equalsIgnoreCase(trimmed) || "Idle".equalsIgnoreCase(trimmed);
    }

    /**
     * 判断最近动作时间戳是否在采集周期的窗口内
     * <p>
     * 条件：{@code 0 <= collectedAt - timestamp < 15分钟}。
     * <ul>
     *   <li>timestamp 为 0 或负数 -> 不活跃</li>
     *   <li>timestamp 轻微领先本机时间 -> 不活跃（禁止未来时间戳永久活跃）</li>
     *   <li>timestamp 明显异常（远超当前时间）-> 不活跃</li>
     * </ul>
     *
     * @param timestamp           last_action.timestamp（epoch 秒）
     * @param collectedAtEpochSecond 采集时刻的 epoch 秒
     * @return true 表示在最近 15 分钟窗口内有动作
     */
    static boolean isRecentAction(long timestamp, long collectedAtEpochSecond) {
        if (timestamp <= 0) {
            return false;
        }
        long diff = collectedAtEpochSecond - timestamp;
        return diff >= 0 && diff < RECENT_ACTION_WINDOW_SECONDS;
    }
}
