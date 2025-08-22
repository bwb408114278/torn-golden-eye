package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOcSlot;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * Torn OC Slot详情响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
public class TornFactionCrimeSlotVO implements TornFactionOcSlot {
    /**
     * 岗位名称
     */
    private String position;
    /**
     * 岗位编号
     */
    @JsonProperty("position_number")
    private Integer positionNumber;
    /**
     * 检查点成功率
     */
    @JsonProperty("checkpoint_pass_rate")
    private Integer checkpointPassRate;
    /**
     * 岗位人员
     */
    private TornFactionCrimeUserVO user;

    public TornFactionOcSlotDO convert2SlotDO(long ocId) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setPosition(this.position + "#" + this.positionNumber);
        if (this.user != null) {
            slot.setUserId(this.user.getId());
            slot.setPassRate(this.checkpointPassRate);
            slot.setJoinTime(DateTimeUtils.convertToDateTime(this.user.getJoinedAt()));
        } else {
            slot.setUserId(null);
            slot.setPassRate(null);
            slot.setJoinTime(null);
        }

        return slot;
    }

    public TornFactionOcUserDO convert2UserDO(long userId, int rank, String name) {
        TornFactionOcUserDO ocUser = new TornFactionOcUserDO();
        ocUser.setUserId(userId);
        ocUser.setRank(rank);
        ocUser.setOcName(name);
        ocUser.setPosition(this.position);
        ocUser.setPassRate(this.checkpointPassRate);
        return ocUser;
    }

    @Override
    public Long getUserId() {
        return this.user == null ? null : user.getId();
    }
}