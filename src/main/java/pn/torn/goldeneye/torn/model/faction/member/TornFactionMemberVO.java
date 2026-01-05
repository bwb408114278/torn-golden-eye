package pn.torn.goldeneye.torn.model.faction.member;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;

/**
 * Torn帮派成员响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.04
 */
@Data
public class TornFactionMemberVO {
    /**
     * ID
     */
    private Long id;
    /**
     * 名称
     */
    private String name;
    /**
     * 状态
     */
    @JsonProperty("last_action")
    private TornUserLastActionVO lastAction;

    public TornUserDO convert2DO(long factionId) {
        TornUserDO user = new TornUserDO();
        user.setId(this.id);
        user.setNickname(this.name);
        user.setFactionId(factionId);
        return user;
    }
}