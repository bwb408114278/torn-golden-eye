package pn.torn.goldeneye.torn.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.faction.TornFactionVO;

import java.time.LocalDateTime;

/**
 * Torn用户响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Data
public class TornUserVO {
    /**
     * 用户ID
     */
    @JsonProperty("player_id")
    private Long playerId;
    /**
     * 用户昵称
     */
    private String name;
    /**
     * 注册日期
     */
    private LocalDateTime signup;
    /**
     * 所在帮派
     */
    private TornFactionVO faction;

    public TornUserDO convert2DO() {
        TornUserDO user = new TornUserDO();
        user.setId(this.playerId);
        user.setNickname(this.name);
        user.setFactionId(this.faction == null ? 0 : this.faction.getFactionId());
        user.setRegisterTime(this.signup);
        return user;
    }
}