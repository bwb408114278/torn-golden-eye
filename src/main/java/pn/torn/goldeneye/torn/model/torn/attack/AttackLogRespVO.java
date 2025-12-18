package pn.torn.goldeneye.torn.model.torn.attack;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 战斗Log响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackLogRespVO {
    /**
     * log数据
     */
    @JsonProperty("attacklog")
    private AttackLogListVO attackLog;
}