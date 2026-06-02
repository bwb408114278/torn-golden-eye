package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.TornStockStrategyFeatureDAO;
import pn.torn.goldeneye.repository.model.torn.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.StockStrategyFeatureUpsert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 股票特征构建逻辑层
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
@Service
@RequiredArgsConstructor
public class StockFeatureBuildService {
    private final TornStockStrategyFeatureDAO featureDao;
    private final StockRollingFeatureEngine featureEngine;
    private static final int UPSERT_BATCH_SIZE = 1000;

    /**
     * 构建时间范围内的特征值
     */
    @Transactional(rollbackFor = Exception.class)
    public void buildBetween(LocalDateTime startTime, LocalDateTime endTime) {
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
        List<StockPricePoint> points = featureDao.selectHistoryPointsBetween(startTime, endTime);
        if (CollectionUtils.isEmpty(points)) {
            return;
        }

        List<StockStrategyFeatureUpsert> features = featureEngine.addAndCalculate(points);
        if (CollectionUtils.isEmpty(features)) {
            return;
        }

        for (int fromIndex = 0; fromIndex < features.size(); fromIndex += UPSERT_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + UPSERT_BATCH_SIZE, features.size());
            featureDao.batchUpsertFeatures(features.subList(fromIndex, toIndex));
        }
    }
}