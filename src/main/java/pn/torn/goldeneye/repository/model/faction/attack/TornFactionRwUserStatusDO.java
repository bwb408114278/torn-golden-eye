package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.constants.torn.enums.user.TornPlaneTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornTravelStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornTravelTargetEnum;
import pn.torn.goldeneye.repository.model.BaseDO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;

/**
 * 帮派Rw用户状态表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_rw_user_status", autoResultMap = true)
@NoArgsConstructor
public class TornFactionRwUserStatusDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * RW ID
     */
    private Long rwId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 用户状态
     */
    private String state;
    /**
     * 旅行目标
     */
    private String travelTarget;
    /**
     * 旅行状态
     */
    private String travelType;
    /**
     * 飞机类型
     */
    private String planeType;

    public TornFactionRwUserStatusDO(long factionId, long rwId, TornFactionMemberVO member) {
        this(member);
        this.factionId = factionId;
        this.rwId = rwId;
        this.userId = member.getId();
        this.nickname = member.getName();
    }

    public TornFactionRwUserStatusDO(long id, TornFactionMemberVO member) {
        this(member);
        this.id = id;
    }

    private TornFactionRwUserStatusDO(TornFactionMemberVO member) {
        this.state = member.getStatus().getState();
        if ("Traveling".equals(this.state) || "Abroad".equals(this.state)) {
            TornTravelTargetEnum taget = TornTravelTargetEnum.textContain(member.getStatus().getDescription());
            TornTravelStatusEnum travelStatus = TornTravelStatusEnum.textStart(member.getStatus().getState());
            this.travelTarget = taget == null ? "" : taget.getCode();
            this.travelType = travelStatus == null ? "" : travelStatus.getCode();
            this.planeType = TornPlaneTypeEnum.imageOfCode(member.getStatus().getPlaneImageType());
        } else {
            this.travelTarget = "";
            this.travelType = "";
            this.planeType = "";
        }
    }
}