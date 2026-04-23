package pn.torn.goldeneye.torn.model.user;

import lombok.Data;

/**
 * Torn用户状态响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.12.18
 */
@Data
public class TornUserLastActionVO {
    /**
     * 在线颜色
     */
    private String status;
    /**
     * 上次操作时间
     */
    private long timestamp;
    /**
     * 状态描述
     */
    private String relative;
}
