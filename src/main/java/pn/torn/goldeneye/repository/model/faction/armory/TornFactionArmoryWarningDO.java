package pn.torn.goldeneye.repository.model.faction.armory;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 帮派物资告警表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_armory_warning", autoResultMap = true)
public class TornFactionArmoryWarningDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 物品ID
     */
    private Long itemId;
    /**
     * 物品昵称
     */
    private String itemNickname;
    /**
     * 告警数量
     */
    private Integer warningQty;
}