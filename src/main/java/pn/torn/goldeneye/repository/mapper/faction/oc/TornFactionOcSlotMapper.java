package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.oc.OcSuccessRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIdleRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn Oc Slot 数据库访问层
 *
 * @author Bai
 * @version 1.2.6
 * @since 2025.07.29
 */
@Mapper
public interface TornFactionOcSlotMapper extends BaseMapper<TornFactionOcSlotDO> {
    /**
     * 查询OC空转排行榜
     *
     * @param fromDate  起始时间
     * @param toDate    结束时间
     * @param factionId 帮派ID
     * @param limit     限制行数
     * @return OC空转排行榜
     */
    List<TornFactionOcIdleRankDO> queryIdleRanking(@Param("fromDate") LocalDateTime fromDate,
                                                   @Param("toDate") LocalDateTime toDate,
                                                   @Param("factionId") long factionId,
                                                   @Param("limit") int limit);

    /**
     * 查询OC成功率排行榜
     *
     * @param factionId 帮派ID，0为所有帮派
     * @param sinceDate 起始日期（近90天）
     * @param sortAsc   是否升序（非酋榜用）
     * @param limit     排行限制
     * @return 排行列表
     */
    List<OcSuccessRankDO> querySuccessRanking(@Param("factionId") long factionId,
                                              @Param("sinceDate") LocalDateTime sinceDate,
                                              @Param("sortAsc") boolean sortAsc,
                                              @Param("limit") int limit);
}
