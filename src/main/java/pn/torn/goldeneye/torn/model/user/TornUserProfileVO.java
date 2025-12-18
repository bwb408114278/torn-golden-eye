package pn.torn.goldeneye.torn.model.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * Torn用户详情响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.10.09
 */
@Data
public class TornUserProfileVO {
    /**
     * 用户ID
     */
    private Long id;
    /**
     * 用户昵称
     */
    private String name;
    /**
     * 注册日期
     */
    private Long signedUp;
    /**
     * 所在帮派
     */
    @JsonProperty("faction_id")
    private Long factionId;
    /**
     * 状态
     */
    private TornUserStatusVO status;

    public TornUserDO convert2DO() {
        TornUserDO user = new TornUserDO();
        user.setId(this.id);
        user.setNickname(this.name);
        user.setFactionId(this.factionId == null ? 0 : this.factionId);
        user.setRegisterTime(DateTimeUtils.convertToDateTime(this.signedUp));
        return user;
    }
}