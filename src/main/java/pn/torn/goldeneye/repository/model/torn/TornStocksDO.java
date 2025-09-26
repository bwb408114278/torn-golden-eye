package pn.torn.goldeneye.repository.model.torn;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;

/**
 * Torn股票表
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stocks", autoResultMap = true)
public class TornStocksDO extends BaseDO {
    /**
     * 股票ID
     */
    private Integer id;
    /**
     * 股票名称
     */
    private String stocksName;
    /**
     * 股票缩写
     */
    private String stocksShortname;
    /**
     * 当前价格
     */
    private BigDecimal currentPrice;
    /**
     * 分红收益类型
     */
    private String benefitType;
    /**
     * 分红收益周期
     */
    private Integer benefitPeriod;
    /**
     * 分红所需股数
     */
    private Long benefitReq;
    /**
     * 分红描述
     */
    private String benefitDesc;
    /**
     * 利润
     */
    private Long profit;
    /**
     * 日利润
     */
    private Long dailyProfit;
    /**
     * 基础分红成本
     */
    private Long baseCost;
}