package pn.torn.goldeneye.repository.mapper.faction.armory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.armory.ItemUseRankingDO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帮派物品使用记录数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Mapper
public interface TornFactionItemUsedMapper extends BaseMapper<TornFactionItemUsedDO> {
    /**
     * 查询物品使用排行榜
     *
     * @param itemName 物品名称
     * @param fromDate 开始时间
     * @param toDate   结束时间
     * @return 排行榜列表
     */
    List<ItemUseRankingDO> queryItemUseRanking(@Param("itemName") String itemName,
                                               @Param("fromDate") LocalDateTime fromDate,
                                               @Param("toDate") LocalDateTime toDate);
}