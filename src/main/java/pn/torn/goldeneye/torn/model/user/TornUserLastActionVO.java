package pn.torn.goldeneye.torn.model.user;

import lombok.Data;

/**
 * Torn用户状态响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
public class TornUserLastActionVO {
    /**
     * 在线颜色
     */
    private String status;
    /**
     * 状态
     */
    private Long timestamp;
    /**
     * 状态描述
     */
    private String relative;
}
