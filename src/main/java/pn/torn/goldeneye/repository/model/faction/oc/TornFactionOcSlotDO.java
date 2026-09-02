package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOcSlot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn OC Slot表
 *
 * @author Bai
 * @version 1.6.0
 * @since 2025.07.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc_slot", autoResultMap = true)
public class TornFactionOcSlotDO extends BaseDO implements TornFactionOcSlot {
    /**
     * ID
     */
    private Long id;
    /**
     * OC ID
     */
    private Long ocId;
    /**
     * 岗位
     */
    private String position;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 成功率
     */
    private Integer passRate;
    /**
     * 加入时间
     */
    private LocalDateTime joinTime;
    /**
     * 准备进度
     */
    private BigDecimal progress;
    /**
     * 消耗品ID
     */
    private Long outcomeItemId;
    /**
     * 消耗品状态
     */
    private String outcomeItemStatus;
    /**
     * 消耗品价格
     */
    private Long outcomeItemValue;
    /**
     * 当前同步快照中的道具需求ID；无需求或无法确认时为null。
     */
    private Integer requiredItemId;
    /**
     * 当前同步快照中的道具可用性；null表示本次同步无法确认，不等价于不可用。
     */
    private Boolean requiredItemAvailable;
}
