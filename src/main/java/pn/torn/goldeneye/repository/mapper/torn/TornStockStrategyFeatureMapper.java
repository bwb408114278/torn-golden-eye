package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.StockStrategyFeaturePoint;
import pn.torn.goldeneye.repository.model.torn.StockStrategyFeatureUpsert;
import pn.torn.goldeneye.repository.model.torn.TornStockStrategyFeatureDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票策略预计算特征库访问层
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.05.29
 */
@Mapper
public interface TornStockStrategyFeatureMapper extends BaseMapper<TornStockStrategyFeatureDO> {
    /**
     * 查询最近的股票特征
     *
     * @param analysisTime 分析时间
     * @return 股票特征点列表
     */
    List<StockStrategyFeaturePoint> selectLatestFeatures(@Param("analysisTime") LocalDateTime analysisTime);

    /**
     * 查询时间范围内价格点
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 价格点列表
     */
    List<StockPricePoint> selectHistoryPointsBetween(@Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime);

    /**
     * 批量更新或插入股票特征
     *
     * @param features 特征值更新列表
     * @return 影响行数
     */
    int batchUpsertFeatures(@Param("features") List<StockStrategyFeatureUpsert> features);
}