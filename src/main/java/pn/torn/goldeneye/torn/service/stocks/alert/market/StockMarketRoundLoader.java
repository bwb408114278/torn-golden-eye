package pn.torn.goldeneye.torn.service.stocks.alert.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票市场轮次快照加载器 - 事务外一次性批量读取本轮决策所需的全部数据
 * <p>
 * 按轮次一次批量加载本轮全部股票15分钟bar、正式特征、所有正式活跃批次与
 * 正式/各α轨道槽位状态,严禁循环逐股票查询Mapper产生N+1问题。
 * 本加载器仅负责纯读取,不做任何业务判断或状态变更。
 * <p>
 * 启用轨道按组合编码去重:同一组合的多条轨道共用组合编码,每个组合每轮只查询一次,
 * 累加结果再按主键去重,保证快照中没有重复行。
 * <p>
 * 加载内容:
 * <ol>
 *   <li>本轮bar: {@code bar15mDao.selectByBarStartTime(roundTime)}</li>
 *   <li>本轮特征: {@code feature15mDao.selectByBarStartTime(roundTime)}</li>
 *   <li>所有正式活跃批次: {@code virtualBatchDao.selectActiveFormalBatches()}</li>
 *   <li>所有α轨道活跃批次: 按启用轨道的组合编码去重后逐组合读取(FORMAL与ALPHA_SHADOW账本)</li>
 *   <li>正式与各α轨道槽位状态: {@code portfolioSlotDao.selectAllByPortfolioCode(...)},每组合只查一次</li>
 * </ol>
 *
 * @author Bai
 * @version 1.6.5
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
     * 虚拟交易批次持久层
     */
    private final TornStockVirtualBatchDAO virtualBatchDao;

    /**
     * 组合仓位槽位持久层
     */
    private final TornStockPortfolioSlotDAO portfolioSlotDao;

    /**
     * α相位轨道注册表
     */
    private final StockAlphaTrackRegistry trackRegistry;

    /**
     * 一次批量加载本轮决策所需的全部数据快照,返回不可变RoundSnapshot值对象。
     * <p>
     * 全部数据通过各自的批量查询方法一次性读取,不产生N+1查询。
     * 本方法不参与事务,调用方在事务外获取快照后再进入事务执行业务逻辑。
     *
     * @param roundTime 本轮bar开始时间
     * @return 本轮全部数据快照
     */
    public RoundSnapshot loadRoundSnapshot(LocalDateTime roundTime) {
        log.debug("加载本轮市场快照, roundTime={}", roundTime);
        List<TornStockMarketBar15mDO> bars = bar15mDao.selectByBarStartTime(roundTime,
                Stock15mBarBuildService.BUILD_VERSION);
        List<TornStockStrategyFeature15mDO> features = feature15mDao.selectByBarStartTime(roundTime,
                Stock15mFeatureBuildService.FEATURE_VERSION);
        List<TornStockVirtualBatchDO> activeBatches = new ArrayList<>(
                virtualBatchDao.selectActiveFormalBatches());
        List<TornStockPortfolioSlotDO> allSlots = new ArrayList<>(
                portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE));
        for (String portfolioCode : enabledPortfolioCodes()) {
            activeBatches.addAll(virtualBatchDao.selectActiveAlphaBatches(portfolioCode));
            allSlots.addAll(portfolioSlotDao.selectAllByPortfolioCode(portfolioCode));
        }
        List<TornStockVirtualBatchDO> distinctBatches = distinctBatchesById(activeBatches);
        List<TornStockPortfolioSlotDO> distinctSlots = distinctSlotsById(allSlots);
        log.debug("本轮市场快照加载完成, bars={}, features={}, activeBatches={}, slots={}",
                bars.size(), features.size(), distinctBatches.size(), distinctSlots.size());
        return new RoundSnapshot(bars, features, distinctBatches, distinctSlots, roundTime);
    }

    /**
     * 返回启用轨道涉及的组合编码,按首次出现顺序去重。
     * <p>
     * 同一组合的多条轨道(如两条影子轨道)共用组合编码,去重可保证每组合每轮只查询一次。
     *
     * @return 去重后的组合编码列表
     */
    private List<String> enabledPortfolioCodes() {
        Set<String> portfolioCodes = new LinkedHashSet<>();
        for (StockAlphaPhaseTrack track : trackRegistry.enabledTracks()) {
            portfolioCodes.add(track.portfolioCode());
        }
        return new ArrayList<>(portfolioCodes);
    }

    /**
     * 按主键去重活跃批次,保持首次出现顺序;无主键对象不参与索引。
     *
     * @param batches 多来源活跃批次
     * @return 按主键去重后的活跃批次
     */
    private List<TornStockVirtualBatchDO> distinctBatchesById(List<TornStockVirtualBatchDO> batches) {
        Map<Long, TornStockVirtualBatchDO> byId = new LinkedHashMap<>();
        for (TornStockVirtualBatchDO batch : batches) {
            if (batch != null && batch.getId() != null) {
                byId.putIfAbsent(batch.getId(), batch);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 按主键去重槽位,保持首次出现顺序;无主键对象不参与索引。
     *
     * @param slots 多来源槽位
     * @return 按主键去重后的槽位
     */
    private List<TornStockPortfolioSlotDO> distinctSlotsById(List<TornStockPortfolioSlotDO> slots) {
        Map<Long, TornStockPortfolioSlotDO> byId = new LinkedHashMap<>();
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot != null && slot.getId() != null) {
                byId.putIfAbsent(slot.getId(), slot);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 轮次快照值对象 - 封装本轮决策所需的全部只读数据
     * <p>
     * 不可变record,由 {@link StockMarketRoundLoader#loadRoundSnapshot(LocalDateTime)} 构造,
     * 供下游策略计算、资格判断、批次进出等业务流程共享读取,避免在事务内重复查询。
     *
     * @param bars          本轮全部股票15分钟bar
     * @param features      本轮全部股票15分钟策略特征
     * @param activeBatches 所有正式与各α轨道活跃批次
     * @param slots         正式组合与各α轨道组合的全部槽位状态
     * @param roundTime     本轮bar开始时间
     */
    public record RoundSnapshot(
            List<TornStockMarketBar15mDO> bars,
            List<TornStockStrategyFeature15mDO> features,
            List<TornStockVirtualBatchDO> activeBatches,
            List<TornStockPortfolioSlotDO> slots,
            LocalDateTime roundTime) {
    }
}
