package pn.torn.goldeneye.repository.model.torn;

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
 * @version 0.5.0
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
     * 市值
     */
    private Long marketCap;
    /**
     * 总股数
     */
    private Long totalShares;
    /**
     * 投资人数
     */
    private Integer investors;
    /**
     * 记录时间
     */
    private LocalDateTime regDateTime;
}