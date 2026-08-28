package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 活跃度 Redis Key 构造工具（V2 legacy 只读 + V3 读写）
 * <p>
 * V3与V2判定口径不同（V3将Idle从主活跃度降级为独立暗化证据），Key前缀隔离，不能混算。
 * V2 Key仅保留给查询端在TTL存续期内兼容读取，采集侧不再写入V2新数据。
 *
 * <p><strong>V3 个人维度（每天 96 位 Bitmap，TTL 30 天）</strong></p>
 * <pre>
 * activity:v3:user:observed:{userId}:{yyyy-MM-dd}
 * activity:v3:user:active:{userId}:{yyyy-MM-dd}
 * activity:v3:user:idle:{userId}:{yyyy-MM-dd}
 * </pre>
 *
 * <p><strong>V3 帮派维度（每天最多 96 字节槽值或 96 位 Bitmap，TTL 30 天）</strong></p>
 * <pre>
 * activity:v3:faction:active-count:{factionId}:{yyyy-MM-dd}  每槽 1 字节有效活跃人数
 * activity:v3:faction:idle-count:{factionId}:{yyyy-MM-dd}    每槽 1 字节 idle-only 人数
 * activity:v3:faction:member-count:{factionId}:{yyyy-MM-dd}  每槽 1 字节有效成员数
 * activity:v3:faction:observed:{factionId}:{yyyy-MM-dd}      96 位 Bitmap
 * </pre>
 *
 * <p><strong>V3 日终归档索引（TTL 30 天）</strong></p>
 * <pre>
 * activity:v3:archive:users:{yyyy-MM-dd}     当日 observed 的 userId Set
 * activity:v3:archive:factions:{yyyy-MM-dd}  当日成功采集的 factionId Set
 * activity:v3:archive:dates                  日期 ZSET（member=yyyy-MM-dd，score=epochDay，TTL 30天）
 * </pre>
 *
 * <p><strong>V2 legacy 个人维度（只读，TTL 自然过期后不得伪造）</strong></p>
 * <pre>
 * activity:v2:user:observed:{userId}:{yyyy-MM-dd}
 * activity:v2:user:status-active:{userId}:{yyyy-MM-dd}
 * activity:v2:user:recent-action:{userId}:{yyyy-MM-dd}
 * </pre>
 *
 * <p><strong>V2 legacy 帮派快照（只读，旧V2覆盖写损坏后独立前缀重新积累）</strong></p>
 * <pre>
 * activity:v2:faction-snapshot-v2:online-count:{factionId}:{yyyy-MM-dd} 每槽 1 字节
 * activity:v2:faction-snapshot-v2:member-count:{factionId}:{yyyy-MM-dd} 每槽 1 字节
 * activity:v2:faction-snapshot-v2:observed:{factionId}:{yyyy-MM-dd}     96 位 Bitmap
 * </pre>
 *
 * <p><strong>名称缓存（V2/V3共用）</strong></p>
 * <pre>
 * activity:v2:user:names     Hash&lt;userId, latestUserName&gt;
 * activity:v2:faction:names  Hash&lt;factionId, latestFactionName&gt;
 * </pre>
 *
 * <p><strong>Gold+ 来源快照（TTL 7 天）</strong></p>
 * <pre>
 * activity:v3:tracked-factions:gold-plus
 * </pre>
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.21
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityRedisKeys {

    static final String V2_PREFIX = "activity:v2:";
    static final String V3_PREFIX = "activity:v3:";

    /**
     * V3 个人维度子前缀
     */
    private static final String V3_USER_OBSERVED = V3_PREFIX + "user:observed:";
    private static final String V3_USER_ACTIVE = V3_PREFIX + "user:active:";
    private static final String V3_USER_IDLE = V3_PREFIX + "user:idle:";

    /**
     * V3 帮派维度子前缀
     */
    private static final String V3_FACTION_ACTIVE_COUNT = V3_PREFIX + "faction:active-count:";
    private static final String V3_FACTION_IDLE_COUNT = V3_PREFIX + "faction:idle-count:";
    private static final String V3_FACTION_MEMBER_COUNT = V3_PREFIX + "faction:member-count:";
    private static final String V3_FACTION_OBSERVED = V3_PREFIX + "faction:observed:";

    /**
     * V3 日终归档索引子前缀
     */
    private static final String V3_ARCHIVE_USERS = V3_PREFIX + "archive:users:";
    private static final String V3_ARCHIVE_FACTIONS = V3_PREFIX + "archive:factions:";
    private static final String V3_ARCHIVE_DATES = V3_PREFIX + "archive:dates";
    private static final String V3_TRACKED_GOLD_PLUS = V3_PREFIX + "tracked-factions:gold-plus";

    /**
     * V2 legacy 个人维度子前缀
     */
    private static final String USER_OBSERVED = V2_PREFIX + "user:observed:";
    private static final String USER_STATUS_ACTIVE = V2_PREFIX + "user:status-active:";
    private static final String USER_RECENT_ACTION = V2_PREFIX + "user:recent-action:";

    /**
     * V2 legacy 帮派快照独立子前缀，隔离旧 V2 覆盖写产生的损坏数据
     */
    private static final String FACTION_SNAPSHOT_V2_PREFIX = V2_PREFIX + "faction-snapshot-v2:";
    private static final String FACTION_ONLINE_COUNT = FACTION_SNAPSHOT_V2_PREFIX + "online-count:";
    private static final String FACTION_MEMBER_COUNT = FACTION_SNAPSHOT_V2_PREFIX + "member-count:";
    private static final String FACTION_OBSERVED = FACTION_SNAPSHOT_V2_PREFIX + "observed:";

    /**
     * 名称缓存 Hash key
     */
    static final String USER_NAME_CACHE_KEY = V2_PREFIX + "user:names";
    static final String FACTION_NAME_CACHE_KEY = V2_PREFIX + "faction:names";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");


    // ==================== V3 个人维度 ====================

    /**
     * 构建 V3 个人 observed Bitmap key：表示该槽 API 成功返回了该成员
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String v3UserObserved(long userId, LocalDate date) {
        return V3_USER_OBSERVED + userId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V3 个人有效活跃 Bitmap key：该槽 status 为 Online 或 15 分钟内有动作
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String v3UserActive(long userId, LocalDate date) {
        return V3_USER_ACTIVE + userId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V3 个人 idle-only Bitmap key：该槽 status 为 Idle 且无近期动作
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String v3UserIdle(long userId, LocalDate date) {
        return V3_USER_IDLE + userId + ":" + date.format(DATE_FMT);
    }

    // ==================== V3 帮派维度 ====================

    /**
     * 构建 V3 帮派 active-count key：最多 96 字节，按槽偏移写入有效活跃人数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String v3FactionActiveCount(long factionId, LocalDate date) {
        return V3_FACTION_ACTIVE_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V3 帮派 idle-count key：最多 96 字节，按槽偏移写入 idle-only 人数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String v3FactionIdleCount(long factionId, LocalDate date) {
        return V3_FACTION_IDLE_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V3 帮派 member-count key：最多 96 字节，按槽偏移写入该次响应的有效成员数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String v3FactionMemberCount(long factionId, LocalDate date) {
        return V3_FACTION_MEMBER_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V3 帮派 observed Bitmap key：表示该槽采集成功
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String v3FactionObserved(long factionId, LocalDate date) {
        return V3_FACTION_OBSERVED + factionId + ":" + date.format(DATE_FMT);
    }

    // ==================== V3 日终归档索引 ====================

    /**
     * 构建 V3 用户归档索引 Set key：当日存在 observed 采样的 userId 集合
     *
     * @param date 日期
     * @return Redis key
     */
    public static String v3ArchiveUsers(LocalDate date) {
        return V3_ARCHIVE_USERS + date.format(DATE_FMT);
    }

    /**
     * 构建 V3 帮派归档索引 Set key：当日成功采集的 factionId 集合
     *
     * @param date 日期
     * @return Redis key
     */
    public static String v3ArchiveFactions(LocalDate date) {
        return V3_ARCHIVE_FACTIONS + date.format(DATE_FMT);
    }

    /**
     * V3 归档候选日期 ZSET，member 为日期文本，score 为 epoch day，TTL 为 30 天。
     *
     * @return 归档候选日期 ZSET key
     */
    public static String v3ArchiveDates() {
        return V3_ARCHIVE_DATES;
    }

    /**
     * 最后一次成功 HoF 刷新的 Gold+ 帮派来源 Set，不包含配置帮派并使用 7 天 TTL。
     *
     * @return Gold+ 来源 Set key
     */
    public static String v3TrackedGoldPlus() {
        return V3_TRACKED_GOLD_PLUS;
    }

    // ==================== V2 legacy 个人维度（只读） ====================

    /**
     * 构建 V2 legacy 个人 observed Bitmap key：表示该槽 API 成功返回了该成员
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String userObserved(long userId, LocalDate date) {
        return USER_OBSERVED + userId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V2 legacy 个人 status-active Bitmap key：该槽 status 为 Online 或 Idle
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String userStatusActive(long userId, LocalDate date) {
        return USER_STATUS_ACTIVE + userId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V2 legacy 个人 recent-action Bitmap key：该槽最近 15 分钟有动作
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis key
     */
    public static String userRecentAction(long userId, LocalDate date) {
        return USER_RECENT_ACTION + userId + ":" + date.format(DATE_FMT);
    }

    // ==================== V2 legacy 帮派快照（只读） ====================

    /**
     * 构建 V2 legacy 帮派 online-count key：最多 96 字节，按槽偏移写入V2口径估算在线人数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String factionOnlineCount(long factionId, LocalDate date) {
        return FACTION_ONLINE_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V2 legacy 帮派 member-count key：最多 96 字节，按槽偏移写入该次响应的有效成员数
     *
     * @param factionId 帮派 ID
     * @param date      日期
     * @return Redis key
     */
    public static String factionMemberCount(long factionId, LocalDate date) {
        return FACTION_MEMBER_COUNT + factionId + ":" + date.format(DATE_FMT);
    }

    /**
     * 构建 V2 legacy 帮派 observed Bitmap key：表示该槽采集成功
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
