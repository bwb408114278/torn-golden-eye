package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OC收益数据库访问层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.10
 */
@Mapper
public interface TornFactionOcBenefitMapper extends BaseMapper<TornFactionOcBenefitDO> {
    /**
     * 查询OC收益排行榜
     *
     * @param factionId 帮派ID
     * @param fromDate  开始时间
     * @param toDate    结束时间
     * @return 排行榜列表
     */
    List<TornFactionOcBenefitRankDO> queryBenefitRanking(@Param("factionId") long factionId,
                                                         @Param("fromDate") LocalDateTime fromDate,
                                                         @Param("toDate") LocalDateTime toDate);

    /**
     * 查询OC大锅饭收益排行榜
     *
     * @param factionId    帮派ID
     * @param fromDate     开始时间
     * @param toDate       结束时间
     * @param reassignList 大锅饭OC名称列表
     * @param yearMonth    年月
     * @return 排行榜列表
     */
    List<TornFactionOcBenefitRankDO> queryIncomeRanking(@Param("factionId") long factionId,
                                                        @Param("fromDate") LocalDateTime fromDate,
                                                        @Param("toDate") LocalDateTime toDate,
                                                        @Param("reassignList") List<String> reassignList,
                                                        @Param("yearMonth") String yearMonth);
}