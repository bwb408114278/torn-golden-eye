package pn.torn.goldeneye.repository.model.racing;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * SMTHPC赛事主表
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_racing_race")
public class TornRacingRaceDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * Torn赛事ID
     */
    private Long raceId;
    /**
     * 赛事名称快照
     */
    private String title;
    /**
     * 业务日期（开赛时间对应的Torn日）
     */
    private LocalDate businessDate;
    /**
     * 开赛时间（北京时间）
     */
    private LocalDateTime startTime;
    /**
     * 结束时间（北京时间）
     */
    private LocalDateTime endTime;
    /**
     * 抓取时的赛事状态
     */
    private String status;
    /**
     * 数据抓取完成时间（北京时间）
     */
    private LocalDateTime capturedTime;
}
