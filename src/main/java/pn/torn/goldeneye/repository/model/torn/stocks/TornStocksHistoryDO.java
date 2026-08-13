package pn.torn.goldeneye.repository.model.torn.stocks;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn股票历史表
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.01.26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stocks_history", autoResultMap = true)
public class TornStocksHistoryDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 股票ID
     */
    private Integer stocksId;
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
     * 市值；Torn API 正常写值，外部补数未提供时允许为 {@code null}，禁止以 0 代表未知
     */
    private Long marketCap;
    /**
     * 总股数
     */
    private Long totalShares;
    /**
     * 投资人数；Torn API 正常写值，外部补数固定为 {@code null}，禁止以 0 代表未知
     */
    private Integer investors;
    /**
     * 记录时间
     */
    private LocalDateTime regDateTime;
    /**
     * 数据来源（{@code TORN_API} 实时采集 / {@code TORNSY_BACKFILL} Tornsy 历史回填）
     */
    private String dataSource;
}