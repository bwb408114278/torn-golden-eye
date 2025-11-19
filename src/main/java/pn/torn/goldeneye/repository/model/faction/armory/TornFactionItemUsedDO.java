package pn.torn.goldeneye.repository.model.faction.armory;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * 帮派物品使用记录表
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_item_used", autoResultMap = true)
public class TornFactionItemUsedDO extends BaseDO {
    /**
     * ID
     */
    private String id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String userNickname;
    /**
     * 使用类型
     */
    private String useType;
    /**
     * 物品名称
     */
    private String itemName;
    /**
     * 使用时间
     */
    private LocalDateTime useTime;
}