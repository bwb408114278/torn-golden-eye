package pn.torn.goldeneye.constants.torn.enums.racing;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Torn赛车赛道枚举
 *
 * <p>赛道名称字典：抓取时按赛道ID解析出名称并随赛事一并落库为快照，
 * 展示侧只读快照，不因赛道字典变化而改写历史数据。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@AllArgsConstructor
@Getter
public enum TornRaceTrackEnum {
    /**
     * Uptown
     */
    UPTOWN(6, "Uptown"),
    /**
     * Withdrawal
     */
    WITHDRAWAL(7, "Withdrawal"),
    /**
     * Underdog
     */
    UNDERDOG(8, "Underdog"),
    /**
     * Parkland
     */
    PARKLAND(9, "Parkland"),
    /**
     * Docks
     */
    DOCKS(10, "Docks"),
    /**
     * Commerce
     */
    COMMERCE(11, "Commerce"),
    /**
     * Two Islands
     */
    TWO_ISLANDS(12, "Two Islands"),
    /**
     * Industrial
     */
    INDUSTRIAL(15, "Industrial"),
    /**
     * Vector
     */
    VECTOR(16, "Vector"),
    /**
     * Mudpit
     */
    MUDPIT(17, "Mudpit"),
    /**
     * Hammerhead
     */
    HAMMERHEAD(18, "Hammerhead"),
    /**
     * Sewage
     */
    SEWAGE(19, "Sewage"),
    /**
     * Meltdown
     */
    MELTDOWN(20, "Meltdown"),
    /**
     * Speedway
     */
    SPEEDWAY(21, "Speedway"),
    /**
     * Stone Park
     */
    STONE_PARK(23, "Stone Park"),
    /**
     * Convict
     */
    CONVICT(24, "Convict");

    /**
     * Torn赛道ID
     */
    private final int trackId;
    /**
     * 赛道名称
     */
    private final String title;

    /**
     * 按赛道ID解析赛道名称。
     *
     * @param trackId Torn赛道ID
     * @return 赛道名称；ID为null或未收录时返回null，由调用方按缺失赛道降级展示
     */
    public static String titleOf(Integer trackId) {
        if (trackId == null) {
            return null;
        }

        for (TornRaceTrackEnum value : values()) {
            if (value.trackId == trackId) {
                return value.title;
            }
        }
        return null;
    }
}
