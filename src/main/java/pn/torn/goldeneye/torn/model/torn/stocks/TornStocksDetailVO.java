package pn.torn.goldeneye.torn.model.torn.stocks;

import lombok.Data;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksHistoryDO;

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
    private int id;
    /**
     * 名称
     */
    private String name;
    /**
     * 缩写
     */
    private String acronym;
    /**
     * 资金市场
     */
    private TornStocksMarketVO market;
    /**
     * 分红
     */
    private TornStocksBonusVO bonus;

    public TornStocksDO convert2DO(long profit, long dailyProfit, long baseCost) {
        TornStocksDO stocks = new TornStocksDO();
        stocks.setId(this.id);
        stocks.setStocksName(this.name);
        stocks.setStocksShortname(this.acronym);
        stocks.setCurrentPrice(this.market.getPrice());
        stocks.setBenefitType(this.bonus.isPassive() ? "passive" : "active");
        stocks.setBenefitPeriod(this.bonus.getFrequency());
        stocks.setBenefitReq(this.bonus.getRequirement());
        stocks.setBenefitDesc(this.bonus.getDescription());
        stocks.setProfit(profit);
        stocks.setYearProfit(dailyProfit);
        stocks.setBaseCost(baseCost);
        return stocks;
    }

    public TornStocksHistoryDO convert2HistoryDO(LocalDateTime regDatetime) {
        TornStocksHistoryDO history = new TornStocksHistoryDO();
        history.setStocksId(this.id);
        history.setStocksName(this.name);
        history.setStocksShortname(this.acronym);
        history.setCurrentPrice(this.market.getPrice());
        history.setMarketCap(this.market.getCap());
        history.setTotalShares(this.market.getShares());
        history.setInvestors(this.market.getInvestors());
        history.setRegDateTime(regDatetime);
        return history;
    }
}