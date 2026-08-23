package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockMarketBar15mMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟K线(bar)持久层类
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.07.24
 */
@Repository
public class TornStockMarketBar15mDAO extends ServiceImpl<TornStockMarketBar15mMapper, TornStockMarketBar15mDO> {

    /**
     * 按bar开始时间和构建版本批量查询全部股票bar,避免逐股查询产生N+1问题
     *
     * @param barStartTime bar开始时间
     * @param buildVersion bar构建版本
     * @return 该时间点指定版本的全部股票bar列表
     */
    public List<TornStockMarketBar15mDO> selectByBarStartTime(LocalDateTime barStartTime,
                                                              String buildVersion) {
        return baseMapper.selectByBarStartTime(barStartTime, buildVersion);
    }

    /**
     * 按时间范围批量查询全部股票bar,避免逐桶查询产生N+1问题
     *
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion bar构建版本
     * @return 按股票ID和bar时间升序排列的bar列表
     */
    public List<TornStockMarketBar15mDO> selectByTimeRange(LocalDateTime startTime,
                                                           LocalDateTime endTime,
                                                           String buildVersion) {
        return baseMapper.selectByTimeRange(startTime, endTime, buildVersion);
    }

    /**
     * 按股票集合和时间范围批量查询bar。
     *
     * @param stocksIds    股票ID列表
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion 构建版本
     * @return bar列表
     */
    public List<TornStockMarketBar15mDO> selectByStocksAndTimeRange(
            List<Integer> stocksIds, LocalDateTime startTime, LocalDateTime endTime, String buildVersion) {
        return baseMapper.selectByStocksAndTimeRange(stocksIds, startTime, endTime, buildVersion);
    }

    /**
     * 查询指定时点前每支股票最新可用bar，供日报一次性计算开放仓位市值。
     *
     * @param stocksIds     股票ID列表
     * @param cutoffTime    摘要允许参与估值的最新bar开始时间
     * @param minBarEndTime 摘要允许参与估值的最早bar结束时间(含)
     * @param buildVersion  构建版本
     * @return 每支股票至多一条最新可用bar
     */
    public List<TornStockMarketBar15mDO> selectLatestUsableByStocks(
            List<Integer> stocksIds, LocalDateTime cutoffTime, LocalDateTime minBarEndTime, String buildVersion) {
        return baseMapper.selectLatestUsableByStocks(stocksIds, cutoffTime, minBarEndTime, buildVersion);
    }

    /**
     * 批量按唯一键执行 UPSERT，空列表直接短路。
     * <p>
     * 供全范围派生数据重建使用，单批大小由调用方控制在 500 条以内。
     *
     * @param bars 待插入或更新的bar列表
     */
    public void upsertBars(List<TornStockMarketBar15mDO> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        baseMapper.upsertBars(bars);
    }

    /**
     * 按单支股票、时间范围与构建版本批量查询bar。
     * <p>
     * 供 feature 顺序批处理使用，避免一次加载全市场 30 天 bar。
     *
     * @param stocksId     股票ID
     * @param startTime    起始时间（含）
     * @param endTime      结束时间（含）
     * @param buildVersion bar构建版本
     * @return 按bar时间升序排列的bar列表
     */
    public List<TornStockMarketBar15mDO> selectByStockAndTimeRange(
            Integer stocksId, LocalDateTime startTime, LocalDateTime endTime, String buildVersion) {
        return baseMapper.selectByStockAndTimeRange(stocksId, startTime, endTime, buildVersion);
    }

    /**
     * 批量查询指定截止时间前每支股票可用bar的首尾时间,供月度状态证据窗口定位。
     *
     * @param stocksIds    股票ID列表
     * @param cutoffTime   证据截止时间(不含)
     * @param buildVersion bar构建版本
     * @return 每支股票一条证据首尾时间记录
     */
    public List<TornStockMarketBar15mDO> selectUsableEvidenceEdges(
            List<Integer> stocksIds, LocalDateTime cutoffTime, String buildVersion) {
        return baseMapper.selectUsableEvidenceEdges(stocksIds, cutoffTime, buildVersion);
    }

    /**
     * 按股票集合和时间范围批量查询可用bar,供月度状态证据窗口加载,避免N+1。
     *
     * @param stocksIds    股票ID列表
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion bar构建版本
     * @return 可用bar列表,按股票ID和bar时间升序
     */
    public List<TornStockMarketBar15mDO> selectUsableByStocksAndTimeRange(
            List<Integer> stocksIds, LocalDateTime startTime, LocalDateTime endTime, String buildVersion) {
        return baseMapper.selectUsableByStocksAndTimeRange(stocksIds, startTime, endTime, buildVersion);
    }
}
