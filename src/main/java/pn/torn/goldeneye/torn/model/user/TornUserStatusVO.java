package pn.torn.goldeneye.torn.model.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn用户状态响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.20
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
     * 飞机图片类型
     */
    @JsonProperty("plane_image_type")
    private String planeImageType;
}
