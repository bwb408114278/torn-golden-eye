package pn.torn.goldeneye.torn.model.faction.revive;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwReviveDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;

/**
 * Torn帮派复活记录
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@Data
public class TornFactionReviveVO {
    /**
     * 复活ID
     */
    private Long id;
    /**
     * 复活者
     */
    private ReviveUserVO reviver;
    /**
     * 被复活目标
     */
    private ReviveTargetVO target;
    /**
     * 成功率
     */
    @JsonProperty("success_chance")
    private BigDecimal successChance;
    /**
     * 复活结果
     */
    private String result;
    /**
     * 复活时间戳
     */
    private Long timestamp;

    public TornFactionRwReviveDO convert2DO(TornFactionRwDO rw) {
        TornFactionRwReviveDO revive = new TornFactionRwReviveDO();
        revive.setId(this.id);
        revive.setRwId(rw.getId());
        revive.setFactionId(rw.getFactionId());
        revive.setReviverId(this.reviver.getId());
        revive.setReviverName(this.reviver.getName());
        revive.setReviverFactionId(this.reviver.getFaction() == null ? null : this.reviver.getFaction().getId());
        revive.setReviverFactionName(this.reviver.getFaction() == null ? null : this.reviver.getFaction().getName());
        revive.setSkill(this.reviver.getSkill());
        revive.setTargetId(this.target.getId());
        revive.setTargetName(this.target.getName());
        revive.setTargetFactionId(this.target.getFaction() == null ? null : this.target.getFaction().getId());
        revive.setTargetFactionName(this.target.getFaction() == null ? null : this.target.getFaction().getName());
        revive.setSuccessChance(this.successChance);
        revive.setSuccess("success".equalsIgnoreCase(this.result));
        revive.setReviveTime(DateTimeUtils.convertToDateTime(this.timestamp));
        return revive;
    }

    /**
     * 复活用户
     */
    @Data
    public static class ReviveUserVO {
        /**
         * 用户ID
         */
        private Long id;
        /**
         * 用户昵称
         */
        private String name;
        /**
         * 所在帮派
         */
        private ReviveFactionVO faction;
        /**
         * 复活技能
         */
        private BigDecimal skill;
    }

    /**
     * 被复活目标
     */
    @Data
    public static class ReviveTargetVO {
        /**
         * 用户ID
         */
        private Long id;
        /**
         * 用户昵称
         */
        private String name;
        /**
         * 所在帮派
         */
        private ReviveFactionVO faction;
        /**
         * 住院原因
         */
        @JsonProperty("hospital_reason")
        private String hospitalReason;
        /**
         * 是否提前出院
         */
        @JsonProperty("early_discharge")
        private Boolean earlyDischarge;
        /**
         * 最后行动时间戳
         */
        @JsonProperty("last_action")
        private Long lastAction;
        /**
         * 在线状态
         */
        @JsonProperty("online_status")
        private String onlineStatus;
    }

    /**
     * 帮派信息
     */
    @Data
    public static class ReviveFactionVO {
        /**
         * 帮派ID
         */
        private Long id;
        /**
         * 帮派名称
         */
        private String name;
    }
}
