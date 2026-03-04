package pn.torn.goldeneye.torn.model.user.travel;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户旅行数据响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.04
 */
@Data
public class TornUserTravelDataVO {
    /**
     * 目的地
     */
    private String destination;
    /**
     * 方式
     */
    private String method;
    /**
     * 起飞时间
     */
    @JsonProperty("departed_at")
    private long departedAt;
    /**
     * 到达时间
     */
    @JsonProperty("arrival_at")
    private long arrivalAt;
    /**
     * 剩余时间
     */
    @JsonProperty("time_left")
    private int timeLeft;
}