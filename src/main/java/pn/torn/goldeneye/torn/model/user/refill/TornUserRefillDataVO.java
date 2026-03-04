package pn.torn.goldeneye.torn.model.user.refill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户Refill数据响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.04
 */
@Data
public class TornUserRefillDataVO {
    /**
     * 能量
     */
    private boolean energy;
    /**
     * 勇气
     */
    private boolean nerve;
    /**
     * Token
     */
    private boolean token;
    /**
     * 特殊数量
     */
    @JsonProperty("special_count")
    private int specialCount;
}