package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn股票15分钟K线(bar)表
 * <p>
 * 将分钟级历史采样按15分钟桶聚合为标准K线，用于策略特征计算与决策。
 * 仅记录实际采样得到的OHLC与质量信息，不补齐缺失样本。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_market_bar_15m", autoResultMap = true)
public class TornStockMarketBar15mDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 股票简称快照(构建bar时记录,防止后续改名导致歧义)
     */
    private String stocksShortname;
    /**
     * 15分钟桶开始时间(桶左闭边界)
     */
    private LocalDateTime barStartTime;
    /**
     * 15分钟桶结束时间(桶右开边界,不含该时点)
     */
    private LocalDateTime barEndTime;
    /**
     * 桶内第一条实际分钟采样时间
     */
    private LocalDateTime firstSampleTime;
    /**
     * 桶内最后一条实际分钟采样时间
     */
    private LocalDateTime lastSampleTime;
    /**
     * 桶内第一条实际价格(开盘价)
     */
    private BigDecimal firstPrice;
    /**
     * 桶内最后一条实际价格(决策/成交参考价)
     */
    private BigDecimal lastPrice;
    /**
     * 桶内实际采样最低价
     */
    private BigDecimal lowPrice;
    /**
     * 桶内实际采样最高价
     */
    private BigDecimal highPrice;
    /**
     * 去重后的分钟采样数量
     */
    private Integer sampleCount;
    /**
     * 构建时发现的重复分钟记录数量
     */
    private Integer duplicateCount;
    /**
     * 桶结束时间到最后一次采样的秒数(衡量尾部缺失程度)
     */
    private Integer tailGapSeconds;
    /**
     * 是否满足正式bar可用标准(采样数与尾部缺口均达标)
     */
    private Boolean usable;
    /**
     * 不可用原因编码(usable=false时填充,如SAMPLE_TOO_FEW、TAIL_GAP_TOO_LARGE)
     */
    private String qualityReason;
    /**
     * bar构建规则版本(标识生成本桶所用的聚合逻辑版本)
     */
    private String buildVersion;
    /**
     * 本桶使用的最大原始历史记录ID(便于溯源与增量重建)
     */
    private Long sourceMaxHistoryId;
}
