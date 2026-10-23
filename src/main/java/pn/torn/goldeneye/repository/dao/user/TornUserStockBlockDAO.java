package pn.torn.goldeneye.repository.dao.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.user.TornUserStockBlockMapper;
import pn.torn.goldeneye.repository.model.user.TornUserStockBlockDO;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Torn用户股票屏蔽持久层类
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Repository
public class TornUserStockBlockDAO extends ServiceImpl<TornUserStockBlockMapper, TornUserStockBlockDO> {
    /**
     * 查询用户已屏蔽的股票ID集合
     *
     * @param userId Torn用户ID
     * @return 屏蔽的股票ID集合; 用户为空或无屏蔽时返回空集合
     */
    public Set<Integer> selectBlockedStocksIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }

        return lambdaQuery()
                .select(TornUserStockBlockDO::getStocksId)
                .eq(TornUserStockBlockDO::getUserId, userId)
                .list().stream()
                .map(TornUserStockBlockDO::getStocksId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 批量保存用户屏蔽的股票
     *
     * <p>调用方需先排除已屏蔽股票; 部分唯一索引
     * {@code uk_user_stock_block_user_stock} 作为并发重复写入的兜底。</p>
     *
     * @param userId    Torn用户ID
     * @param stocksIds 待屏蔽的股票ID集合
     */
    public void saveBlocks(Long userId, Collection<Integer> stocksIds) {
        if (userId == null || CollectionUtils.isEmpty(stocksIds)) {
            return;
        }

        List<TornUserStockBlockDO> rows = stocksIds.stream()
                .distinct()
                .map(stocksId -> {
                    TornUserStockBlockDO row = new TornUserStockBlockDO();
                    row.setUserId(userId);
                    row.setStocksId(stocksId);
                    return row;
                })
                .toList();
        saveBatch(rows);
    }

    /**
     * 批量取消用户对股票的屏蔽(逻辑删除)
     *
     * @param userId    Torn用户ID
     * @param stocksIds 待取消屏蔽的股票ID集合
     */
    public void removeBlocks(Long userId, Collection<Integer> stocksIds) {
        if (userId == null || CollectionUtils.isEmpty(stocksIds)) {
            return;
        }

        lambdaUpdate()
                .eq(TornUserStockBlockDO::getUserId, userId)
                .in(TornUserStockBlockDO::getStocksId, stocksIds)
                .remove();
    }
}
