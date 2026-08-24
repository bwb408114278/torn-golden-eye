package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

import java.util.List;

/**
 * RW攻击频率图片所需的双方聚合数据。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@Data
public class RwAttackFrequencySummaryVO {
    /**
     * 统计窗口。
     */
    private RwStatWindowVO window;

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
