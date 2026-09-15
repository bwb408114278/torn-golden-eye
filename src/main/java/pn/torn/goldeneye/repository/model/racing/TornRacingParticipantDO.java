package pn.torn.goldeneye.repository.model.racing;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;

/**
 * SMTHPC赛事参赛选手明细表
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_racing_participant")
public class TornRacingParticipantDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * Torn赛事ID
     */
    private Long raceId;
    /**
     * 选手Torn用户ID
     */
    private Long userId;
    /**
     * 抓取时昵称快照，本地无记录的非联盟选手为空
     */
    private String nickname;
    /**
     * 抓取时帮派ID快照
     */
    private Long factionId;
    /**
     * 抓取时是否为联盟选手
     */
    private Boolean isAlliance;
    /**
     * 赛事名次（API原始值）
     */
    private Integer position;
    /**
     * 完赛用时（秒），撞车为空
     */
    private BigDecimal raceTime;
    /**
     * 最快圈用时（秒）
     */
    private BigDecimal bestLapTime;
    /**
     * 是否撞车
     */
    private Boolean hasCrashed;
}
