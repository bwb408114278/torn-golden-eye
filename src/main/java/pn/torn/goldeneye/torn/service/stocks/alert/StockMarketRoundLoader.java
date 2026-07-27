package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票市场轮次快照加载器 - 事务外一次性批量读取本轮决策所需的全部数据
 * <p>
 * 按轮次一次批量加载本轮全部股票15分钟bar、正式特征、当月已确认月度状态、
 * 所有正式活跃批次、所有信号边沿状态与正式槽位状态,严禁循环逐股票查询Mapper
 * 产生N+1问题。本加载器仅负责纯读取,不做任何业务判断或状态变更。
 * <p>
 * 加载内容:
 * <ol>
 *   <li>本轮bar: {@code bar15mDAO.selectByBarStartTime(roundTime)}</li>
 *   <li>本轮特征: {@code feature15mDAO.selectByBarStartTime(roundTime)}</li>
 *   <li>当月已确认月度状态: {@code monthlyStateDAO.selectConfirmedByMonth(roundTime当月1日)}</li>
 *   <li>所有正式活跃批次: {@code virtualBatchDAO.selectActiveFormalBatches()}</li>
 *   <li>所有信号边沿状态: {@code signalStateDAO.selectAll()}</li>
 *   <li>正式槽位状态: {@code portfolioSlotDAO.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE)}</li>
 * </ol>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMarketRoundLoader {

    /**
     * 15分钟bar持久层
     */
    private final TornStockMarketBar15mDAO bar15mDao;

    /**
     * 15分钟策略特征持久层
     */
    private final TornStockStrategyFeature15mDAO feature15mDao;

    /**
     * 月度风格状态持久层
     */
    private final TornStockMonthlyStateDAO monthlyStateDao;

    /**
     * 虚拟交易批次持久层
     */
    private final TornStockVirtualBatchDAO virtualBatchDao;

    /**
     * 信号边沿状态持久层
     */
    private final TornStockSignalStateDAO signalStateDao;

    /**
     * 组合仓位槽位持久层
     */
    private final TornStockPortfolioSlotDAO portfolioSlotDao;

    /**
     * 一次批量加载本轮决策所需的全部数据快照,返回不可变RoundSnapshot值对象。
     * <p>
     * 全部6类数据通过各自的批量查询方法一次性读取,不产生N+1查询。
     * 本方法不参与事务,调用方在事务外获取快照后再进入事务执行业务逻辑。
     *
     * @param roundTime 本轮bar开始时间,同时作为当月生效月份的取值依据
     * @return 本轮全部数据快照
     */
    public RoundSnapshot loadRoundSnapshot(LocalDateTime roundTime) {
        log.debug("加载本轮市场快照, roundTime={}", roundTime);
        List<TornStockMarketBar15mDO> bars = bar15mDao.selectByBarStartTime(roundTime,
                Stock15mBarBuildService.BUILD_VERSION);
        List<TornStockStrategyFeature15mDO> features = feature15mDao.selectByBarStartTime(roundTime,
                Stock15mFeatureBuildService.FEATURE_VERSION);
        List<TornStockMonthlyStateDO> monthlyStates =
                monthlyStateDao.selectConfirmedByMonth(roundTime.toLocalDate().withDayOfMonth(1));
        List<TornStockVirtualBatchDO> activeBatches = virtualBatchDao.selectActiveFormalBatches();
        List<TornStockVirtualBatchDO> shadowBatches = virtualBatchDao.selectActiveShadowBatches();
        List<TornStockSignalStateDO> signalStates = signalStateDao.selectAll();
        List<TornStockPortfolioSlotDO> slots =
                portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        log.debug("本轮市场快照加载完成, bars={}, features={}, monthlyStates={}, formalBatches={}, shadowBatches={}, signalStates={}, slots={}",
                bars.size(), features.size(), monthlyStates.size(),
                activeBatches.size(), shadowBatches.size(), signalStates.size(), slots.size());
        return new RoundSnapshot(bars, features, monthlyStates, activeBatches, shadowBatches,
                signalStates, slots, roundTime);
    }

    /**
     * 轮次快照值对象 - 封装本轮决策所需的全部只读数据
     * <p>
     * 不可变record,由 {@link StockMarketRoundLoader#loadRoundSnapshot(LocalDateTime)} 构造,
     * 供下游策略计算、资格判断、批次进出等业务流程共享读取,避免在事务内重复查询。
     *
     * @param bars          本轮全部股票15分钟bar
     * @param features      本轮全部股票15分钟策略特征
     * @param monthlyStates 当月已确认的月度风格状态
     * @param activeBatches 所有正式活跃批次
     * @param shadowBatches 所有活跃影子批次(UNLIMITED_SHADOW)
     * @param signalStates  所有信号边沿状态
     * @param slots         正式组合全部槽位状态
     * @param roundTime     本轮bar开始时间
     */
    public record RoundSnapshot(
            List<TornStockMarketBar15mDO> bars,
            List<TornStockStrategyFeature15mDO> features,
            List<TornStockMonthlyStateDO> monthlyStates,
            List<TornStockVirtualBatchDO> activeBatches,
            List<TornStockVirtualBatchDO> shadowBatches,
            List<TornStockSignalStateDO> signalStates,
            List<TornStockPortfolioSlotDO> slots,
            LocalDateTime roundTime
    ) {
    }
}
