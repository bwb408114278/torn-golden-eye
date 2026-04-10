package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.torn.TornItemHistoryMapper;
import pn.torn.goldeneye.repository.model.torn.ItemTrendDO;
import pn.torn.goldeneye.repository.model.torn.TornItemHistoryDO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Torn物品历史持久层类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.01.26
 */
@Repository
public class TornItemHistoryDAO extends ServiceImpl<TornItemHistoryMapper, TornItemHistoryDO> {
    /**
     * 查询物品历史数据
     *
     * @param itemIds    物品ID列表
     * @param targetDate 目标日期
     * @return 历史数据列表
     */
    public List<TornItemHistoryDO> queryItemHistory(List<Integer> itemIds, LocalDate targetDate) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return List.of();
        }

        return lambdaQuery()
                .in(TornItemHistoryDO::getItemId, itemIds)
                .eq(TornItemHistoryDO::getRegDate, targetDate)
                .list();
    }

    /**
     * 查询物品环比数据
     *
     * @param itemIds    物品ID列表
     * @param targetDate 目标日期
     * @return 环比数据列表
     */
    public List<ItemTrendDO> queryItemComparison(List<Integer> itemIds, LocalDate targetDate) {
        Map<Integer, ItemTrendDO> trendMap = baseMapper.queryItemComparison(itemIds, targetDate)
                .stream()
                .collect(Collectors.toMap(ItemTrendDO::getItemId, Function.identity()));
        return itemIds.stream()
                .map(trendMap::get)
                .filter(Objects::nonNull)
                .toList();
    }
}