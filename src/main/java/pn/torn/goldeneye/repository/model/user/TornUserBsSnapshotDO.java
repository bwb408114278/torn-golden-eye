package pn.torn.goldeneye.repository.model.user;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn用户BS快照表
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.27
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_user_bs_snapshot", autoResultMap = true)
public class TornUserBsSnapshotDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 记录日期
     */
    private LocalDate recordDate;
    /**
     * 总BS
     */
    private Long total;
    /**
     * 力量
     */
    private Long strength;
    /**
     * 防御
     */
    private Long defense;
    /**
     * 速度
     */
    private Long speed;
    /**
     * 敏捷
     */
    private Long dexterity;
}