package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.AllArgsConstructor;
import lombok.Data;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * OC收益排名查询参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.10
 */
@Data
public class OcBenefitRankingQuery {
    /**
     * 开始时间
     */
    private LocalDateTime fromDate;
    /**
     * 结束时间
     */
    private LocalDateTime toDate;
    /**
     * 年月
     */
    private String yearMonth;
    /**
     * 帮派ID
     */
    private long factionId;
    /**
     * 大锅饭帮派列表
     */
    private List<Long> reassignFactionList;
    /**
     * 大锅饭OC列表，需要根据帮派排除该部分收益查询
     */
    private List<FactionOcExclusion> factionOcExclusions;
    /**
     * 包括普通帮派收益
     */
    private boolean includeNormalBenefit;
    /**
     * 包含大锅饭帮派收益
     */
    private boolean includeReassignBenefit;
    /**
     * 用户ID
     */
    private long userId;
    /**
     * 排行榜数量
     */
    private int limit;

    public OcBenefitRankingQuery(long factionId, long userId, LocalDate baseMonth) {
        this.fromDate = baseMonth.withDayOfMonth(1).atTime(0, 0, 0);
        this.toDate = baseMonth.withDayOfMonth(baseMonth.lengthOfMonth()).atTime(23, 59, 59);
        this.yearMonth = toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        this.reassignFactionList = TornConstants.REASSIGN_OC_FACTION;
        this.factionId = factionId;
        this.userId = userId;
        this.limit = 30;
        if (factionId == 0L) {
            // 为每个大锅饭派系构建各自的排除列表
            this.factionOcExclusions = TornConstants.REASSIGN_OC_FACTION.stream()
                    .map(fid -> new FactionOcExclusion(fid, TornConstants.ROTATION_OC_NAME.get(fid)))
                    .toList();
            this.includeNormalBenefit = true;
            this.includeReassignBenefit = true;
            this.limit = 50;
        } else if (TornConstants.REASSIGN_OC_FACTION.contains(factionId)) {
            this.factionOcExclusions = List.of(new FactionOcExclusion(factionId,
                    TornConstants.ROTATION_OC_NAME.getOrDefault(factionId, List.of())));
            this.includeNormalBenefit = false;
            this.includeReassignBenefit = true;
        } else {
            this.factionOcExclusions = List.of();
            this.includeNormalBenefit = true;
            this.includeReassignBenefit = false;
        }
    }

    public OcBenefitRankingQuery(long userId, LocalDate baseMonth) {
        this.fromDate = baseMonth.withDayOfMonth(1).atTime(0, 0, 0);
        this.toDate = baseMonth.withDayOfMonth(baseMonth.lengthOfMonth()).atTime(23, 59, 59);
        this.yearMonth = toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        this.factionId = 0L;
        this.reassignFactionList = TornConstants.REASSIGN_OC_FACTION;
        this.factionOcExclusions = TornConstants.REASSIGN_OC_FACTION.stream()
                .map(fid -> new FactionOcExclusion(fid, TornConstants.ROTATION_OC_NAME.get(fid)))
                .toList();
        this.includeNormalBenefit = true;
        this.includeReassignBenefit = true;
        this.userId = userId;
    }

    @Data
    @AllArgsConstructor
    public static class FactionOcExclusion {
        private Long factionId;
        private List<String> ocList;
    }
}