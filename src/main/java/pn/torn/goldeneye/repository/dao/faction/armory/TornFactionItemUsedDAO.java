package pn.torn.goldeneye.repository.dao.faction.armory;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.armory.TornFactionItemUsedMapper;
import pn.torn.goldeneye.repository.model.faction.armory.ItemUseRankingDO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帮派物品使用记录持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Repository
public class TornFactionItemUsedDAO extends ServiceImpl<TornFactionItemUsedMapper, TornFactionItemUsedDO> {
    /**
     * 查询物品使用排行榜
     *
     * @param itemName 物品名称
     * @param fromDate 开始时间
     * @param toDate   结束时间
     * @return 排行榜列表
     */
    public List<ItemUseRankingDO> queryItemUseRanking(String itemName, LocalDateTime fromDate, LocalDateTime toDate) {
        return baseMapper.queryItemUseRanking(itemName, fromDate, toDate);
    }
}