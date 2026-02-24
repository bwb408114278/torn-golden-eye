package pn.torn.goldeneye.torn.model.user.bar;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户条数值VO
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Data
public class TornUserBarNumberVO {
    /**
     * 当前
     */
    private int current;
    /**
     * 最大
     */
    private int maximum;
    /**
     * 增长幅度
     */
    private int increment;
    /**
     * 增长频率（秒）
     */
    private int interval;
    /**
     * 下次增长时间（剩余秒数）
     */
    @JsonProperty("tick_time")
    private int tickTime;
    /**
     * 填满时间（剩余秒数）
     */
    @JsonProperty("full_time")
    private int fullTime;
}
