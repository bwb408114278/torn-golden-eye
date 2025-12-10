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
     * 大锅饭OC列表
     */
    private List<String> reassignOcList;
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
        this(userId, baseMonth);
        this.factionId = factionId;
        this.userId = userId;
        this.limit = 30;

        if (factionId == 0L) {
            this.includeNormalBenefit = true;
            this.includeReassignBenefit = true;
            this.limit = 50;
        } else if (TornConstants.REASSIGN_OC_FACTION.contains(factionId)) {
            this.includeNormalBenefit = false;
            this.includeReassignBenefit = true;
        } else {
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
        this.reassignOcList = TornConstants.ROTATION_OC_NAME;
        this.includeNormalBenefit = true;
        this.includeReassignBenefit = true;
        this.userId = userId;
    }
}

