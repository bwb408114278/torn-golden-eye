package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.List;

/**
 * Torn OC Crime详情响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
public class TornFactionCrimeVO {
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
     * 上级OC ID
     */
    @JsonProperty("previous_crime_id")
    private Long previousCrimeId;
    /**
     * 位置信息
     */
    private List<TornFactionCrimeSlotVO> slots;

    public TornFactionOcDO convert2DO(boolean isCurrent) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(this.id);
        oc.setName(this.name);
        oc.setRank(this.difficulty);
        oc.setStatus(this.status);
        oc.setPreviousOcId(this.previousCrimeId);
        oc.setHasCurrent(isCurrent);

        if (this.readyAt != null) {
            oc.setReadyTime(DateTimeUtils.convertToDateTime(readyAt));
        }

        return oc;
    }
}