package pn.torn.goldeneye.torn.manager.torn.stocks;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStockStrategyFeatureDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeatureUpsert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 股票特征构建逻辑层
 *
 * @author Bai
 * @version 1.2.8
 * @since 2026.06.02
 */
@Service
@RequiredArgsConstructor
public class StockFeatureBuildService {
    private final TornStockStrategyFeatureDAO featureDao;
    private final StockRollingFeatureEngine featureEngine;
    private static final int UPSERT_BATCH_SIZE = 1000;

    /**
     * 手动重建时间范围内的特征值（全量刷新）。
     * <p>通过原子方法 {@code rebuildAndCalculate} 完成 reset + warmup + 计算，
     * 整个过程阻塞实时轮询的增量计算，杜绝竞态条件。
     */
    public void rebuildBetween(LocalDateTime startTime, LocalDateTime endTime) {
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
        List<StockPricePoint> points = featureDao.selectHistoryPointsBetween(startTime, endTime);
        if (CollectionUtils.isEmpty(points)) {
            return;
        }

        List<StockStrategyFeatureUpsert> features = featureEngine.rebuildAndCalculate(points);
        saveFeature(features);
    }

    /**
     * 增量构建时间范围内的特征值（实时轮询调用）。
     */
    public void buildBetween(LocalDateTime startTime, LocalDateTime endTime) {
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
        List<StockPricePoint> points = featureDao.selectHistoryPointsBetween(startTime, endTime);
        if (CollectionUtils.isEmpty(points)) {
            return;
        }

        List<StockStrategyFeatureUpsert> features = featureEngine.addAndCalculate(points);
        saveFeature(features);
    }

    /**
     * 保存股票特征值数据
     */
    private void saveFeature(List<StockStrategyFeatureUpsert> features) {
        if (CollectionUtils.isEmpty(features)) {
            return;
        }

        for (int fromIndex = 0; fromIndex < features.size(); fromIndex += UPSERT_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + UPSERT_BATCH_SIZE, features.size());
            featureDao.batchUpsertFeatures(features.subList(fromIndex, toIndex));
        }
    }
}