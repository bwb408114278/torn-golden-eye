package pn.torn.goldeneye.torn.model.user.racing;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Torn赛车单条成绩响应参数
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Data
public class TornRaceResultVO {
    /**
     * 选手Torn用户ID
     */
    @JsonProperty("driver_id")
    private Long driverId;
    /**
     * 赛事名次（API原始值）
     */
    private Integer position;
    /**
     * 完赛用时（秒）
     */
    @JsonProperty("race_time")
    private BigDecimal raceTime;
    /**
     * 最快圈用时（秒）
     */
    @JsonProperty("best_lap_time")
    private BigDecimal bestLapTime;
    /**
     * 是否撞车
     */
    @JsonProperty("has_crashed")
    private Boolean hasCrashed;
}
