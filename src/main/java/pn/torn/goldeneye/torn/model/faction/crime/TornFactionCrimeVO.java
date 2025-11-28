package pn.torn.goldeneye.torn.model.faction.crime;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOc;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.List;
import java.util.Map;

/**
 * Torn OC Crime详情响应参数
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.29
 */
@Data
public class TornFactionCrimeVO implements TornFactionOc {
    /**
     * Crime id
     */
    private Long id;
    /**
     * Name
     */
    private String name;
    /**
     * 难度
     */
    private Integer difficulty;
    /**
     * 状态
     */
    private String status;
    /**
     * 准备开始时间
     */
    @JsonProperty("ready_at")
    private Long readyAt;
    /**
     * 执行时间
     */
    @JsonProperty("executed_at")
    private Long executedAt;
    /**
     * 上级OC ID
     */
    @JsonProperty("previous_crime_id")
    private Long previousCrimeId;
    /**
     * 位置信息
     */
    private List<TornFactionCrimeSlotVO> slots;
    /**
     * 奖励
     */
    private TornFactionCrimeRewardVO rewards;

    @Override
    public Integer getRank() {
        return this.difficulty;
    }

    public TornFactionOcDO convert2DO(long factionId, Map<Integer, TornItemsDO> itemMap) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(this.id);
        oc.setFactionId(factionId);
        oc.setName(this.name);
        oc.setRank(this.difficulty);
        oc.setStatus(this.status);
        oc.setPreviousOcId(this.previousCrimeId);
        oc.setRewardMoney(getRewardMoney());
        oc.setRewardItems(getRewardItems());
        oc.setRewardItemsValue(getRewardItemsValue(itemMap));

        if (this.readyAt != null) {
            oc.setReadyTime(DateTimeUtils.convertToDateTime(readyAt));
        }

        if (this.executedAt != null) {
            oc.setExecutedTime(DateTimeUtils.convertToDateTime(executedAt));
            oc.setHasNoticed(true);
        } else {
            oc.setHasNoticed(false);
        }

        return oc;
    }

    public Long getRewardMoney() {
        if (this.rewards == null) {
            return 0L;
        }

        return this.rewards.getMoney();
    }

    public String getRewardItems() {
        if (this.rewards == null || CollectionUtils.isEmpty(this.rewards.getItems())) {
            return "";
        }

        StringBuilder rewardBuilder = new StringBuilder();
        for (TornFactionCrimeRewardItemVO item : this.rewards.getItems()) {
            rewardBuilder.append("#").append(item.getId()).append(":").append(item.getQuantity());
        }

        return rewardBuilder.toString().replaceFirst("#", "");
    }

    public String getRewardItemsValue(Map<Integer, TornItemsDO> itemMap) {
        if (this.rewards == null || CollectionUtils.isEmpty(this.rewards.getItems())) {
            return "";
        }

        StringBuilder rewardBuilder = new StringBuilder();
        for (TornFactionCrimeRewardItemVO rewardItem : this.rewards.getItems()) {
            TornItemsDO item = itemMap.get(rewardItem.getId());
            long price = item.getSellPrice() == null ? item.getMarketPrice() : item.getSellPrice();
            long value = price * rewardItem.getQuantity();
            rewardBuilder.append("#").append(value);
        }

        return rewardBuilder.toString().replaceFirst("#", "");
    }
}