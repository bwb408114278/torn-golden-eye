package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornItemHistoryMapper;
import pn.torn.goldeneye.repository.model.torn.ItemHistoryNoticeDO;
import pn.torn.goldeneye.repository.model.torn.TornItemHistoryDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn物品历史持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.26
 */
@Repository
public class TornItemHistoryDAO extends ServiceImpl<TornItemHistoryMapper, TornItemHistoryDO> {
    /**
     * 查询物品环比数据
     *
     * @param itemIds    物品ID列表
     * @param targetDate 目标日期
     * @return 环比数据列表
     */
    public List<ItemHistoryNoticeDO> queryItemComparison(List<Integer> itemIds, LocalDate targetDate) {
        return baseMapper.queryItemComparison(itemIds, targetDate);
    }
}