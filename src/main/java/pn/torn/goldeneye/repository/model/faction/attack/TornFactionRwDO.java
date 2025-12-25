package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * 帮派Rw表
 *
 * @author Bai
 * @version 0.4.0
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
}