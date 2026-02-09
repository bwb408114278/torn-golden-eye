package pn.torn.goldeneye.repository.model.torn;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 物品历史趋势
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.03
 */
@Data
public class ItemTrendDO {
    private Integer itemId;
    private String itemName;

    // 今日数据
    private Long todayPrice;
    private Long todayCirculation;

    // 上周数据
    private Long lastWeekPrice;
    private Long lastWeekCirculation;
    private BigDecimal lastWeekPriceChange;
    private BigDecimal lastWeekCirculationChange;

    // 上月数据
    private Long lastMonthPrice;
    private Long lastMonthCirculation;
    private BigDecimal lastMonthPriceChange;
    private BigDecimal lastMonthCirculationChange;

    // 上年数据
    private Long lastYearPrice;
    private Long lastYearCirculation;
    private BigDecimal lastYearPriceChange;
    private BigDecimal lastYearCirculationChange;

    // 上年上周数据
    private Long lastYearLastWeekPrice;
    private Long lastYearLastWeekCirculation;

    // 上年上月数据
    private Long lastYearLastMonthPrice;
    private Long lastYearLastMonthCirculation;

    // 上年一周后数据
    private Long lastYearNextWeekPrice;
    private Long lastYearNextWeekCirculation;
    private BigDecimal lastYearNextWeekPriceChange;
    private BigDecimal lastYearNextWeekCirculationChange;
}