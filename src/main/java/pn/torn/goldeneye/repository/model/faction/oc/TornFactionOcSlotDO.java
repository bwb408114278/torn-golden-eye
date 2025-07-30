package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * Torn OC Slot表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc_slot", autoResultMap = true)
public class TornFactionOcSlotDO extends BaseDO {
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
}