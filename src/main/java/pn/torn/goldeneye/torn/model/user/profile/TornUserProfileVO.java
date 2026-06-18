package pn.torn.goldeneye.torn.model.user.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * Torn用户详情响应参数
 *
 * @author Bai
 * @version 1.2.3
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
    /**
     * 状态
     */
    @JsonProperty("last_action")
    private TornUserLastActionVO lastAction;
    /**
     * 血量
     */
    private LifeVO life;

    /**
     * 血量信息
     */
    @Data
    public static class LifeVO {
        /**
         * 当前血量
         */
        private Integer current;
        /**
         * 最大血量
         */
        private Integer maximum;
    }

    public TornUserDO convert2DO() {
        TornUserDO user = new TornUserDO();
        user.setId(this.id);
        user.setNickname(this.name);
        user.setFactionId(this.factionId == null ? 0 : this.factionId);
        user.setRegisterTime(DateTimeUtils.convertToDateTime(this.signedUp));
        return user;
    }
}