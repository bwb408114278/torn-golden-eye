package pn.torn.goldeneye.torn.service.activity.query;

import java.time.LocalDate;

/**
 * 活跃度查询日快照模型
 * <p>
 * 仅作为 loader 与 aggregator 之间的内部日数据传输，不暴露到 Bot 层。
 * 一个快照表达单个目标（用户或帮派）在单个自然日、单一数据版本下的压缩事实；
 * loader 保证同一日期只有一个版本的快照，聚合时不存在跨版本重复累计。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
public sealed interface ActivityDaySnapshot {

    /**
     * 快照归属自然日（Asia/Shanghai）
     *
     * @return 自然日
     */
    LocalDate date();

    /**
     * 是否为 V2 legacy 快照（无法区分 Idle，idleRatio 强制为 0）
     *
     * @return true 表示 V2 legacy
     */
    boolean legacyV2();

    /**
     * 96 位 observed Bitmap（MSB-first 位序，每 15 分钟一槽）
     *
     * @return observed Bitmap，不为 null
     */
    byte[] observedBitmap();

    /**
     * 用户维度日快照
     *
     * @param date           快照归属自然日
     * @param legacyV2       是否为 V2 legacy 快照
     * @param observedBitmap 96 位 observed Bitmap
     * @param activeBitmap   96 位有效活跃 Bitmap；V2 legacy 为 status-active 与 recent-action 按位 OR
     * @param idleBitmap     96 位 idle-only Bitmap，V2 legacy 时为 null
     */
    record UserDay(LocalDate date, boolean legacyV2, byte[] observedBitmap,
                   byte[] activeBitmap, byte[] idleBitmap) implements ActivityDaySnapshot {
    }

    /**
     * 帮派维度日快照
     *
     * @param date           快照归属自然日
     * @param legacyV2       是否为 V2 legacy 快照
     * @param observedBitmap 96 位成功采样标记 Bitmap
     * @param activeCounts   96 字节有效活跃人数槽值；V2 legacy 为 V2 口径估算在线人数
     * @param idleCounts     96 字节 idle-only 人数槽值，V2 legacy 时为 null
     * @param memberCounts   96 字节有效成员数槽值
     */
    record FactionDay(LocalDate date, boolean legacyV2, byte[] observedBitmap,
                      byte[] activeCounts, byte[] idleCounts, byte[] memberCounts) implements ActivityDaySnapshot {
    }
}
