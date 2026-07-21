package pn.torn.goldeneye.torn.service.activity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * V2 活跃度 Redis Key 构造工具
 * <p>
 * V1 与 V2 数据的判定方式、分母和历史帮派口径不同，不能混用。
 * V2 所有 key 统一使用 {@code activity:v2:} 前缀，V1 key 不主动删除，按 TTL 自然过期。
 *
 * <h3>个人维度（每天 96 位 Bitmap，TTL 30 天）</h3>
 * <pre>
 * activity:v2:user:observed:{userId}:{yyyy-MM-dd}
 * activity:v2:user:status-active:{userId}:{yyyy-MM-dd}
 * activity:v2:user:recent-action:{userId}:{yyyy-MM-dd}
 * </pre>
 *
 * <h3>帮派维度（每天 96 槽，TTL 30 天）</h3>
 * <pre>
 * activity:v2:faction:online-count:{factionId}:{yyyy-MM-dd}   定长 String，每槽 1 字节
 * activity:v2:faction:member-count:{factionId}:{yyyy-MM-dd}   定长 String，每槽 1 字节
 * activity:v2:faction:observed:{factionId}:{yyyy-MM-dd}       96 位 Bitmap
 * </pre>
 *
 * <h3>名称缓存</h3>
 * <pre>
 * activity:v2:user:names     Hash&lt;userId, latestUserName&gt;
 * activity:v2:faction:names  Hash&lt;factionId, latestFactionName&gt;
 * </pre>
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
public final class ActivityRedisKeys {

    static final String V2_PREFIX = "activity:v2:";

    /** 个人维度子前缀 */
    private static final String USER_OBSERVED = V2_PREFIX + "user:observed:";
    private static final String USER_STATUS_ACTIVE = V2_PREFIX + "user:status-active:";
    private static final String USER_RECENT_ACTION = V2_PREFIX + "user:recent-action:";

    /** 帮派维度子前缀 */
    private static final String FACTION_ONLINE_COUNT = V2_PREFIX + "faction:online-count:";
    private static final String FACTION_MEMBER_COUNT = V2_PREFIX + "faction:member-count:";
    private static final String FACTION_OBSERVED = V2_PREFIX + "faction:observed:";

    /** 名称缓存 Hash key */
    static final String USER_NAMES_HASH = V2_PREFIX + "user:names";
    static final String FACTION_NAMES_HASH = V2_PREFIX + "faction:names";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ActivityRedisKeys() {
    }

    // ==================== 个人维度 ====================

    /**
     * 构建 observed Bitmap key：表示该槽 API 成功返回了该成员
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String userObserved(long userId, LocalDate date) {
        return USER_OBSERVED + userId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 status-active Bitmap key：表示该槽 status 为 Online 或 Idle
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String userStatusActive(long userId, LocalDate date) {
        return USER_STATUS_ACTIVE + userId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 recent-action Bitmap key：表示该槽最近 15 分钟有动作
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String userRecentAction(long userId, LocalDate date) {
        return USER_RECENT_ACTION + userId + ":" + date.format(DATE_FMT);
    }

    // ==================== 帮派维度 ====================

    /**
     * 构建帮派 online-count key：定长 String，每槽 1 字节保存估算在线人数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String factionOnlineCount(long factionId, LocalDate date) {
        return FACTION_ONLINE_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建帮派 member-count key：定长 String，每槽 1 字节保存该次响应的有效成员数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String factionMemberCount(long factionId, LocalDate date) {
        return FACTION_MEMBER_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建帮派 observed Bitmap key：表示该槽采集成功
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String factionObserved(long factionId, LocalDate date) {
        return FACTION_OBSERVED + factionId + ":" + date.format(DATE_FMT);
    }

    // ==================== 名称缓存 ====================

    /**
     * 用户名称缓存 Hash 的 field
     *
     * @param userId 用户 ID
     * @return Hash field
     */
    public static String userNameField(long userId) {
        return String.valueOf(userId);
    }

    /**
     * 帮派名称缓存 Hash 的 field
     *
     * @param factionId 帮派 ID
     * @return Hash field
     */
    public static String factionNameField(long factionId) {
        return String.valueOf(factionId);
    }
}
