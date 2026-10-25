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
 * @version 1.9.4
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
     * 对外展示的百分制推荐评分，取值1至100；排序内部仍用货币量纲，两者严格单调
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
     * 期望净产出除以期望人天，表示该OC的单位人力产出（货币单位）
     *
     * <p>仅供内部诊断与回放日志使用，不对外展示：金额是估计值，展示成精确金额会被当成账本。</p>
     */
    private BigDecimal expectedNetPerMemberDay;
    /**
     * 除本人之外可填该岗位的人数；null表示成功率矩阵无样本、能力未知
     */
    private Integer substituteCount;
    /**
     * 岗位稀缺度分档，仅用于解释，不参与排序
     */
    private OcRecommendOutcomeEnum outcome;

    public OcRecommendationVO(TornFactionOcDO oc, TornFactionOcSlotDO slot, BigDecimal recommendScore, String reason) {
        this(oc, slot, recommendScore, reason, null, null, null);
    }

    /**
     * 构造带推荐评分口径的推荐结果。
     *
     * @param oc                      候选OC
     * @param slot                    候选空位
     * @param recommendScore          本岗位的百分制推荐评分，取值1至100
     * @param reason                  推荐理由
     * @param expectedNetPerMemberDay 期望净产出除以期望人天
     * @param substituteCount         除本人之外可填该岗位的人数，null表示未知
     * @param outcome                 岗位稀缺度分档
     */
    public OcRecommendationVO(TornFactionOcDO oc, TornFactionOcSlotDO slot, BigDecimal recommendScore, String reason,
                              BigDecimal expectedNetPerMemberDay, Integer substituteCount,
                              OcRecommendOutcomeEnum outcome) {
        this.ocId = oc.getId();
        this.ocName = oc.getName();
        this.rank = oc.getRank();
        this.recommendedPosition = slot.getPosition();
        this.readyTime = oc.getReadyTime();
        this.recommendScore = recommendScore;
        this.reason = reason;
        this.expectedNetPerMemberDay = expectedNetPerMemberDay;
        this.substituteCount = substituteCount;
        this.outcome = outcome;
    }
}
