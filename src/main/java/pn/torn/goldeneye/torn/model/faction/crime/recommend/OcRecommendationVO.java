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
 * @version 1.9.3
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
     * 推荐度评分，口径为本岗位的机会成本（不再是0至100的相对分）
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
    /**
     * 期望净产出除以期望人天，表示该岗位的单位人力产出
     */
    private BigDecimal expectedNetPerMemberDay;
    /**
     * 机会成本，排序键本体：不填这个位帮派会损失多少
     */
    private BigDecimal opportunityCost;
    /**
     * 除本人之外可填该岗位的人数；null表示成功率矩阵无样本、能力未知
     */
    private Integer substituteCount;
    /**
     * 岗位稀缺度分档，仅用于解释，不参与排序
     */
    private OcRecommendOutcomeEnum outcome;

    public OcRecommendationVO(TornFactionOcDO oc, TornFactionOcSlotDO slot, BigDecimal recommendScore, String reason) {
        this(oc, slot, recommendScore, reason, null, null, null, null);
    }

    public OcRecommendationVO(TornFactionOcDO oc, TornFactionOcSlotDO slot, BigDecimal recommendScore, String reason,
                              BigDecimal expectedNetPerMemberDay, BigDecimal opportunityCost,
                              Integer substituteCount, OcRecommendOutcomeEnum outcome) {
        this.ocId = oc.getId();
        this.ocName = oc.getName();
        this.rank = oc.getRank();
        this.recommendedPosition = slot.getPosition();
        this.readyTime = oc.getReadyTime();
        this.recommendScore = recommendScore;
        this.reason = reason;
        this.expectedNetPerMemberDay = expectedNetPerMemberDay;
        this.opportunityCost = opportunityCost;
        this.substituteCount = substituteCount;
        this.outcome = outcome;
    }
}