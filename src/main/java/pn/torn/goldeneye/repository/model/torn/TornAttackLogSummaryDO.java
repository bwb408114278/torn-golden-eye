package pn.torn.goldeneye.repository.model.torn;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn攻击日志表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_attack_log_summary", autoResultMap = true)
public class TornAttackLogSummaryDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 日志ID
     */
    private String logId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 击中数
     */
    private Integer hits;
    /**
     * 失手数
     */
    private Integer misses;
    /**
     * 造成伤害
     */
    private Long damage;
}