package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

import java.util.List;

/**
 * RW攻击频率图片所需的双方聚合数据。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.24
 */
@Data
public class RwAttackFrequencySummaryVO {
    /**
     * 统计窗口，全窗口合并统计时为null。
     */
    private RwStatWindowVO window;

    /**
     * 统计窗口数量，全窗口合并统计时为窗口总数，单窗口为1。
     */
    private int windowCount;

    /**
     * 统计窗口总秒数。
     */
    private long totalWindowSeconds;

    /**
     * 己方总出手次数。
     */
    private int selfAttackCount;

    /**
     * 己方出手用户数。
     */
    private int selfUserCount;

    /**
     * 对方总出手次数。
     */
    private int opponentAttackCount;

    /**
     * 对方出手用户数。
     */
    private int opponentUserCount;

    /**
     * 己方用户出手列表。
     */
    private List<RwUserAttackStatVO> selfUsers;

    /**
     * 对方用户出手列表。
     */
    private List<RwUserAttackStatVO> opponentUsers;
}
