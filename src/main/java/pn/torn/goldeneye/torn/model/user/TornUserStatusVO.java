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
public class TornUserStatusVO {
    /**
     * 状态描述
     */
    private String description;
    /**
     * 状态
     */
    private String state;
    /**
     * 在线颜色
     */
    private String color;
}
