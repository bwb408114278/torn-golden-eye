package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOcSlot;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Torn OC Slot详情响应参数
 *
 * @author Bai
 * @version 0.3.0
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
            slot.setProgress(this.user.getProgress());
        } else {
            slot.setUserId(null);
            slot.setPassRate(null);
            slot.setJoinTime(null);
            slot.setProgress(BigDecimal.ZERO);
        }

        return slot;
    }

    public TornFactionOcUserDO convert2UserDO(long userId, long factionId, int rank, String name) {
        TornFactionOcUserDO ocUser = new TornFactionOcUserDO();
        ocUser.setUserId(userId);
        ocUser.setFactionId(factionId);
        ocUser.setRank(rank);
        ocUser.setOcName(name);
        ocUser.setPosition(this.position);
        ocUser.setPassRate(this.checkpointPassRate);
        return ocUser;
    }

    public Integer getOutcomeItemId() {
        return user.getItemOutcome() == null ? 0 : user.getItemOutcome().getItemId();
    }

    public String getOutcomeItemStatus() {
        return user.getItemOutcome() == null ? "" : user.getItemOutcome().getOutcome();
    }

    public Long getOutcomeItemValue(Map<Integer, TornItemsDO> itemMap) {
        return user.getItemOutcome() == null ? 0L : itemMap.get(user.getItemOutcome().getItemId()).getMarketPrice();
    }

    @Override
    public Long getUserId() {
        return this.user == null ? null : user.getId();
    }
}