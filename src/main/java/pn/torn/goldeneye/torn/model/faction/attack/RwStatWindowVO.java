package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RW对冲窗口业务展示对象。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@Data
public class RwStatWindowVO {
    /**
     * 所属RW ID。
     */
    private Long rwId;

    /**
     * 窗口字母编码。
     */
    private String windowCode;

    /**
     * 窗口开始时间。
     */
    private LocalDateTime startTime;

    /**
     * 窗口结束时间。
     */
    private LocalDateTime endTime;

    /**
     * 是否已经确认。
     */
    private Boolean confirmed;

    /**
     * 己方窗口内出手次数。
     */
    private Integer selfAttackCount;

    /**
     * 对方窗口内出手次数。
     */
    private Integer opponentAttackCount;

    /**
     * 己方窗口内出手人数。
     */
    private Integer selfUserCount;

    /**
     * 对方窗口内出手人数。
     */
    private Integer opponentUserCount;
}
