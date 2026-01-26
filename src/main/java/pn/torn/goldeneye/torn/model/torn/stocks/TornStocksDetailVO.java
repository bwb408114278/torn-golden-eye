package pn.torn.goldeneye.torn.model.torn.stocks;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksHistoryDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn股票详情响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.26
 */
@Data
public class TornStocksDetailVO {
    /**
     * 股票ID
     */
    @JsonProperty("stock_id")
    private int stockId;
    /**
     * 名称
     */
    private String name;
    /**
     * 缩写
     */
    private String acronym;
    /**
     * 当前价格
     */
    @JsonProperty("current_price")
    private BigDecimal currentPrice;
    /**
     * 市值
     */
    @JsonProperty("market_cap")
    private long marketCap;
    /**
     * 总股数
     */
    @JsonProperty("total_shares")
    private long totalShares;
    /**
     * 投资人数
     */
    private int investors;
    /**
     * 分红
     */
    private TornStocksBenefitVO benefit;

    public TornStocksDO convert2DO(long profit, long dailyProfit, long baseCost) {
        TornStocksDO stocks = new TornStocksDO();
        stocks.setId(this.stockId);
        stocks.setStocksName(this.name);
        stocks.setStocksShortname(this.acronym);
        stocks.setCurrentPrice(this.currentPrice);
        stocks.setBenefitType(this.benefit.getType());
        stocks.setBenefitPeriod(this.benefit.getFrequency());
        stocks.setBenefitReq(this.benefit.getRequirement());
        stocks.setBenefitDesc(this.benefit.getDescription());
        stocks.setProfit(profit);
        stocks.setYearProfit(dailyProfit);
        stocks.setBaseCost(baseCost);
        return stocks;
    }

    public TornStocksHistoryDO convert2HistoryDO(LocalDateTime regDatetime) {
        TornStocksHistoryDO history = new TornStocksHistoryDO();
        history.setStocksId(this.stockId);
        history.setStocksName(this.name);
        history.setStocksShortname(this.acronym);
        history.setCurrentPrice(this.currentPrice);
        history.setMarketCap(this.marketCap);
        history.setTotalShares(this.totalShares);
        history.setInvestors(this.investors);
        history.setRegDateTime(regDatetime);
        return history;
    }
}