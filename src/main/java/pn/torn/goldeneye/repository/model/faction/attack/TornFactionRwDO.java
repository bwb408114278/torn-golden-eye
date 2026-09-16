package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 帮派Rw表
 *
 * @author Bai
 * @version 1.6.4
 * @since 2025.12.25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_rw", autoResultMap = true)
public class TornFactionRwDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 帮派名称
     */
    private String factionName;
    /**
     * 对手帮派ID
     */
    private Long opponentFactionId;
    /**
     * 对手帮派名称
     */
    private String opponentFactionName;
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    /**
     * 集合时间
     */
    private LocalTime gatheringTime;
    /**
     * 解散时间
     */
    private LocalTime disbandTime;
    /**
     * 飞书上传后的工作表ID
     */
    private String larksuiteSheetId;
    /**
     * 战争目标分数，登记真赛时写入；null为未知
     */
    private Integer targetScore;
    /**
     * 胜方帮派ID，战争结束后写入；null为未结束或未知
     */
    private Long winnerFactionId;
    /**
     * 我方最终战争分，战争结束后写入；null为未结束或未知
     */
    private Integer factionScore;
    /**
     * 对手最终战争分，战争结束后写入；null为未结束或未知
     */
    private Integer opponentScore;
    /**
     * 对手帮派简称，表头展示用；默认按对手名称首字母生成，支持人工改库修正
     */
    private String opponentShortName;
}