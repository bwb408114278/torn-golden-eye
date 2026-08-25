package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * RW对冲统计窗口持久化对象。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_rw_stat_window", autoResultMap = true)
public class TornFactionRwStatWindowDO extends BaseDO {
    /**
     * 窗口记录ID。
     */
    private Long id;

    /**
     * 所属RW ID。
     */
    private Long rwId;

    /**
     * 用户可引用的窗口字母编码。
     */
    private String windowCode;

    /**
     * 对冲窗口开始时间。
     */
    private LocalDateTime startTime;

    /**
     * 对冲窗口结束时间。
     */
    private LocalDateTime endTime;

    /**
     * 是否已经确认，不再参与后续重新编号。
     */
    private Boolean confirmed;
}
