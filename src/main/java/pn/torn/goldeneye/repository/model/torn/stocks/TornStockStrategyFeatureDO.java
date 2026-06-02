package pn.torn.goldeneye.repository.model.torn.stocks;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票策略预计算特征表
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.05.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_strategy_feature", autoResultMap = true)
public class TornStockStrategyFeatureDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 股票缩写
     */
    private String stocksShortname;
    /**
     * 特征时间
     */
    private LocalDateTime featureTime;
    /**
     * 基础价格
     */
    private BigDecimal basePrice;
    /**
     * 近1日均价
     */
    private BigDecimal ma1d;
    /**
     * 近7日均价
     */
    private BigDecimal ma7d;
    /**
     * 近30日均价
     */
    private BigDecimal ma30d;
    /**
     * 近1日价格偏离度
     */
    private BigDecimal zscore1d;
    /**
     * 近7日价格偏离度
     */
    private BigDecimal zscore7d;
    /**
     * 近30日价格偏离度
     */
    private BigDecimal zscore30d;
    /**
     * RSI指标
     */
    private BigDecimal rsi;
    /**
     * 近1日收益率
     */
    private BigDecimal return1d;
    /**
     * 近7日收益率
     */
    private BigDecimal return7d;
    /**
     * 近14日收益率
     */
    private BigDecimal return14d;
    /**
     * 距离30日低点涨幅
     */
    private BigDecimal pctAbove30dLow;
    /**
     * 距离30日高点跌幅
     */
    private BigDecimal pctAbove30dHigh;
    /**
     * 近30日最低价
     */
    private BigDecimal low30d;
    /**
     * 近30日最高价
     */
    private BigDecimal high30d;
    /**
     * 投资人数
     */
    private Integer latestInvestors;
    /**
     * 近7日投资人数变化
     */
    private Integer investorsChange7d;
}