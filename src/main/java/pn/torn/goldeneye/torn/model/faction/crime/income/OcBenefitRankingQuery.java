package pn.torn.goldeneye.torn.model.faction.crime.income;

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
 * @version 1.2.12
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
     * 大锅饭普通收益排除规则，按扁平规则组织，用于排行榜和个人明细统一过滤
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
            // 为每个大锅饭帮派展开各自的排除规则
            this.factionOcExclusions = loadAllFactionExclusions();
            this.includeNormalBenefit = true;
            this.includeReassignBenefit = true;
            this.limit = 50;
        } else if (TornConstants.REASSIGN_OC_FACTION.contains(factionId)) {
            this.factionOcExclusions = TornConstants.OC_BENEFIT_EXCLUSION_RULES.getOrDefault(factionId, List.of());
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
        this.factionOcExclusions = loadAllFactionExclusions();
        this.includeNormalBenefit = true;
        this.includeReassignBenefit = true;
        this.userId = userId;
    }

    /**
     * 个人普通收益明细查询构造器。
     *
     * <p>使用指定的时间范围，并按用户所属帮派的大锅饭排除规则过滤普通收益明细；
     * 非大锅饭帮派用户不应用任何排除规则。</p>
     *
     * @param factionId 用户所属帮派ID
     * @param userId    用户ID
     * @param fromDate  查询开始时间（含）
     * @param toDate    查询结束时间（含）
     */
    public OcBenefitRankingQuery(long factionId, long userId, LocalDateTime fromDate, LocalDateTime toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.yearMonth = toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        this.factionId = factionId;
        this.userId = userId;
        this.reassignFactionList = TornConstants.REASSIGN_OC_FACTION;
        this.factionOcExclusions = TornConstants.OC_BENEFIT_EXCLUSION_RULES.getOrDefault(factionId, List.of());
        this.includeNormalBenefit = false;
        this.includeReassignBenefit = false;
        this.limit = 30;
    }

    /**
     * 展开所有大锅饭帮派的普通收益排除规则。
     *
     * @return 扁平化后的排除规则列表
     */
    private static List<FactionOcExclusion> loadAllFactionExclusions() {
        return TornConstants.REASSIGN_OC_FACTION.stream()
                .flatMap(fid -> TornConstants.OC_BENEFIT_EXCLUSION_RULES.getOrDefault(fid, List.of()).stream())
                .toList();
    }
}
