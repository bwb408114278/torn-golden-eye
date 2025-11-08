package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OC推荐VO
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
public class OcRecommendationVO {
    /**
     * OC ID
     */
    private Long ocId;
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC级别
     */
    private Integer rank;
    /**
     * 推荐岗位
     */
    private String recommendedPosition;
    /**
     * 推荐度评分
     */
    private BigDecimal recommendScore;
    /**
     * 停转时间
     */
    private LocalDateTime readyTime;
    /**
     * 推荐理由
     */
    private String reason;

    public OcRecommendationVO(TornFactionOcDO oc, TornFactionOcSlotDO slot, BigDecimal recommendScore, String reason) {
        this.ocId = oc.getId();
        this.ocName = oc.getName();
        this.rank = oc.getRank();
        this.recommendedPosition = slot.getPosition();
        this.readyTime = oc.getReadyTime();
        this.recommendScore = recommendScore;
        this.reason = reason;
    }
}