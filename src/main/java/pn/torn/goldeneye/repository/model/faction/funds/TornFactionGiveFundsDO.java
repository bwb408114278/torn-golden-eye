package pn.torn.goldeneye.repository.model.faction.funds;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * 帮派取钱记录表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_give_funds", autoResultMap = true)
public class TornFactionGiveFundsDO extends BaseDO {
    /**
     * ID
     */
    private String id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 取款用户ID
     */
    private Long userId;
    /**
     * 执行用户ID
     */
    private Long handleUserId;
    /**
     * 金额
     */
    private Long amount;
    /**
     * 取款时间
     */
    private LocalDateTime withdrawTime;
}