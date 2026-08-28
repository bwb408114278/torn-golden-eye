package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.model.activity.ActivityEvidence;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;

/**
 * 活跃度V3互斥证据判定纯函数
 * <p>
 * 以单个成员、单个15分钟采样槽为单位产出互斥分类：
 * <pre>
 * recentAction   = 0 &lt;= collectedAt - lastAction.timestamp &lt; 15分钟
 * onlineActive   = last_action.status 为 Online（忽略大小写和首尾空白）
 * effectiveActive = onlineActive OR recentAction
 * idleOnly       = last_action.status 为 Idle AND !recentAction
 * </pre>
 * {@code Idle + recentAction}归入有效活跃；{@code Offline + recentAction}保留对隐藏状态的兼容；
 * {@code last_action}缺失、时间戳为0/负数/未来、未知状态均不构成active或idle，但成员仍是observed。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.21
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityEvidenceClassifier {

    /**
     * 采集周期（分钟），同时作为最近动作证据窗口
     */
    static final int POLL_INTERVAL_MINUTES = 15;

    /**
     * 最近动作证据窗口（秒）
     */
    static final long RECENT_ACTION_WINDOW_SECONDS = POLL_INTERVAL_MINUTES * 60L;


    /**
     * 根据成员的 last_action 信息和采集时间，判定V3互斥活跃证据
     *
     * @param lastAction             成员的 last_action 信息，可为 null
     * @param collectedAtEpochSecond 采集时刻的 epoch 秒
     * @return V3互斥证据判定结果
     */
    public static ActivityEvidence classifyActivity(
            TornUserLastActionVO lastAction,
            long collectedAtEpochSecond) {
        if (lastAction == null) {
            return new ActivityEvidence(false, false, false, false);
        }

        boolean recentAction = isRecentAction(lastAction.getTimestamp(), collectedAtEpochSecond);
        boolean onlineActive = isOnlineStatus(lastAction.getStatus());
        boolean idleOnly = isIdleStatus(lastAction.getStatus()) && !recentAction;
        boolean effectiveActive = onlineActive || recentAction;
        return new ActivityEvidence(onlineActive, recentAction, idleOnly, effectiveActive);
    }

    /**
     * 判断 last_action.status 是否为 Online（忽略大小写和首尾空白）
     *
     * @param status last_action.status 原始值
     * @return true 表示 Online
     */
    static boolean isOnlineStatus(String status) {
        return equalsTrimmedIgnoreCase(status, "Online");
    }

    /**
     * 判断 last_action.status 是否为 Idle（忽略大小写和首尾空白）
     *
     * @param status last_action.status 原始值
     * @return true 表示 Idle
     */
    static boolean isIdleStatus(String status) {
        return equalsTrimmedIgnoreCase(status, "Idle");
    }

    /**
     * 判断最近动作时间戳是否在采集周期的窗口内
     * <p>
     * 条件：{@code 0 <= collectedAt - timestamp < 15分钟}。
     * <ul>
     *   <li>timestamp 为 0 或负数 -> 不活跃</li>
     *   <li>timestamp 轻微领先本机时间 -> 不活跃（禁止未来时间戳永久活跃，fail-closed）</li>
     *   <li>timestamp 明显异常（远超当前时间）-> 不活跃</li>
     * </ul>
     *
     * @param timestamp              last_action.timestamp（epoch 秒）
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

    /**
     * 状态串忽略首尾空白和大小写比较
     *
     * @param status   last_action.status 原始值
     * @param expected 期望状态字面量
     * @return true 表示语义相等
     */
    private static boolean equalsTrimmedIgnoreCase(String status, String expected) {
        return status != null && !status.isBlank() && expected.equalsIgnoreCase(status.trim());
    }
}
