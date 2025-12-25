package pn.torn.goldeneye.torn.model.user.elo;

import lombok.Data;

/**
 * Torn用户个人数据响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.24
 */
@Data
public class TornUserStatsVO {
    /**
     * 数据名称
     */
    private String name;
    /**
     * 数据值
     */
    private int value;
    /**
     * 时间戳
     */
    private long timestamp;
}
