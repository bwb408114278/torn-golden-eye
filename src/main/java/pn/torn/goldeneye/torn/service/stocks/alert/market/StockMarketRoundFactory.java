package pn.torn.goldeneye.torn.service.stocks.alert.market;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.StockRoundTransactionService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.VipStockAlertScheduler;

/**
 * 股票轮次工厂 - 唯一创建 {@link TornStockMarketRoundDO} 的入口,集中填充规则版本与默认值
 * <p>
 * scheduler({@link VipStockAlertScheduler})、历史重建({@link StockHistoryRebuildService})与
 * 事务兜底({@link StockRoundTransactionService})必须通过本工厂创建轮次记录,禁止各自手写
 * 不完整DO。本工厂统一填充数据库NOT NULL字段(roundTime、roundStatus、bar/feature版本、
 * buy/sell/allocation/message四个规则版本、expected/usableStockCount、attemptCount),
 * 避免任一调用方遗漏规则版本导致落库失败或版本事实不一致。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
public class StockMarketRoundFactory {

    /**
     * 创建指定时间与状态的轮次记录(未持久化,含全部规则版本与默认计数)。
     *
     * @param roundTime   轮次时间(业务桶锚点,15分钟对齐)
     * @param roundStatus 初始轮次状态编码(PENDING/PROCESSING等,见 {@code StockRoundStatusEnum})
     * @return 已填充全部NOT NULL字段的轮次记录
     */
    public TornStockMarketRoundDO createRound(LocalDateTime roundTime, String roundStatus) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setRoundTime(roundTime);
        round.setRoundStatus(roundStatus);
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setBuyRuleVersion(StockRuleVersion.BUY);
        round.setSellRuleVersion(StockRuleVersion.SELL);
        round.setAllocationRuleVersion(StockRuleVersion.ALLOCATION);
        round.setMessageRuleVersion(StockRuleVersion.MESSAGE);
        round.setExpectedStockCount(0);
        round.setUsableStockCount(0);
        round.setAttemptCount(0);
        return round;
    }
}
