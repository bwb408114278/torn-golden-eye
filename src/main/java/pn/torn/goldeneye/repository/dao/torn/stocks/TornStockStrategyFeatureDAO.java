package pn.torn.goldeneye.repository.dao.torn.stocks;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.TornStockStrategyFeatureMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeaturePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeatureUpsert;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStockStrategyFeatureDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票策略预计算特征持久层类
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.05.29
 */
@Repository
public class TornStockStrategyFeatureDAO
        extends ServiceImpl<TornStockStrategyFeatureMapper, TornStockStrategyFeatureDO> {
    /**
     * 查询最近的股票特征
     *
     * @param analysisTime 分析时间
     * @return 股票特征点列表
     */
    public List<StockStrategyFeaturePoint> selectLatestFeatures(LocalDateTime analysisTime) {
        return baseMapper.selectLatestFeatures(analysisTime);
    }

    /**
     * 查询时间范围内价格点
     */
    public List<StockPricePoint> selectHistoryPointsBetween(LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.selectHistoryPointsBetween(startTime, endTime);
    }

    /**
     * 批量更新或插入股票特征
     */
    public void batchUpsertFeatures(List<StockStrategyFeatureUpsert> features) {
        baseMapper.batchUpsertFeatures(features);
    }
}