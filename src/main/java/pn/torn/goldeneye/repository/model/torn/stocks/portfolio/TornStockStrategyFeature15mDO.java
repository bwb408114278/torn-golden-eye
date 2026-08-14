package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn股票15分钟bar策略特征表
 * <p>
 * 基于15分钟bar计算策略所需的均线、Z-score、收益率、通道宽度等特征,
 * 供买入/卖出规则引擎消费。
 * <p>
 * <b>空值语义</b>: 窗口指标(ma、zscore、return 等以及 low30d/high30d、width30d、
 * pct_above/below)在对应时间窗口不足或指标不可计算时为{@code null},并非价格或收益为零;
 * 此时{@code strategyReady=false}且{@code dataQualityReason}解释该空值。
 * {@code position30}在高低价相同时为空,不属于指标窗口不可计算。
 *
 * @author Bai
 * @version 1.2.17
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_strategy_feature_15m", autoResultMap = true)
public class TornStockStrategyFeature15mDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 股票简称快照
     */
    private String stocksShortname;
    /**
     * 对应bar的开始时间(特征锚点)
     */
    private LocalDateTime barStartTime;
    /**
     * 参考价格(通常为bar收盘价,用于计算偏离度与收益率)
     */
    private BigDecimal referencePrice;
    /**
     * 近1日(96根bar)移动均价;窗口不足或指标不可计算时为空
     */
    private BigDecimal ma1d;
    /**
     * 近7日移动均价;窗口不足或指标不可计算时为空
     */
    private BigDecimal ma7d;
    /**
     * 近30日移动均价;窗口不足或指标不可计算时为空
     */
    private BigDecimal ma30d;
    /**
     * 参考价相对近1日均值的Z-score;窗口不足或指标不可计算时为空
     */
    private BigDecimal zscore1d;
    /**
     * 参考价相对近7日均值的Z-score;窗口不足或指标不可计算时为空
     */
    private BigDecimal zscore7d;
    /**
     * 参考价相对近30日均值的Z-score;窗口不足或指标不可计算时为空
     */
    private BigDecimal zscore30d;
    /**
     * 近6小时(24根bar)收益率;窗口不足或指标不可计算时为空
     */
    private BigDecimal return6h;
    /**
     * 近1日收益率;窗口不足或指标不可计算时为空
     */
    private BigDecimal return1d;
    /**
     * 近7日收益率;窗口不足或指标不可计算时为空
     */
    private BigDecimal return7d;
    /**
     * 近14日收益率;窗口不足或指标不可计算时为空
     */
    private BigDecimal return14d;
    /**
     * 近30日最低价;窗口不足或指标不可计算时为空
     */
    private BigDecimal low30d;
    /**
     * 近30日最高价;窗口不足或指标不可计算时为空
     */
    private BigDecimal high30d;
    /**
     * 近30日价格带宽;窗口不足或指标不可计算时为空
     */
    private BigDecimal width30d;
    /**
     * 当前价格在30日区间的位置;高低价相同时为空(与窗口不足无关)
     */
    private BigDecimal position30;
    /**
     * 参考价相对30日低点的涨幅;窗口不足或指标不可计算时为空
     */
    private BigDecimal pctAbove30dLow;
    /**
     * 参考价相对30日高点的跌幅;窗口不足或指标不可计算时为空
     */
    private BigDecimal pctBelow30dHigh;
    /**
     * 策略特征是否就绪(样本量充足且无异常)
     */
    private Boolean strategyReady;
    /**
     * 特征不可用原因编码(strategyReady=false时填充,解释窗口指标空值)
     */
    private String dataQualityReason;
    /**
     * 特征计算规则版本
     */
    private String featureVersion;
}
