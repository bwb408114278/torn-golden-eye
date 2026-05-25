package pn.torn.goldeneye.repository.model.user;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn用户数据快照表
 *
 * @author Bai
 * @version 1.1.5
 * @since 2026.05.22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_user_state_snapshot", autoResultMap = true)
public class TornUserStateSnapshotDO extends BaseDO {
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
     * ELO
     */
    private Integer elo;
}