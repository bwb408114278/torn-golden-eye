package pn.torn.goldeneye.torn.model.user.racing;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn赛车时间表响应参数
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Data
public class TornRaceScheduleVO {
    /**
     * 加入起始时间
     */
    @JsonProperty("join_from")
    private Long joinFrom;
    /**
     * 加入结束时间
     */
    @JsonProperty("join_until")
    private Long joinUntil;
    /**
     * 比赛开始时间
     */
    private Long start;
    /**
     * 比赛结束时间
     */
    private Long end;
}