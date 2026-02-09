package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.ItemHistoryNoticeDO;
import pn.torn.goldeneye.repository.model.torn.TornItemHistoryDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn物品历史数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.26
 */
@Mapper
public interface TornItemHistoryMapper extends BaseMapper<TornItemHistoryDO> {
    /**
     * 查询物品环比数据
     *
     * @param itemIds    物品ID列表
     * @param targetDate 目标日期
     * @return 环比数据列表
     */
    List<ItemHistoryNoticeDO> queryItemComparison(@Param("itemIds") List<Integer> itemIds,
                                                  @Param("targetDate") LocalDate targetDate);
}