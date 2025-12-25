package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn战斗日志数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Mapper
public interface TornAttackLogMapper extends BaseMapper<TornAttackLogDO> {
    /**
     * 统计指定时间窗口的玩家数据
     *
     * @param factionId      帮派ID
     * @param windowMinutes  时间窗口长度
     * @param minBattleCount 满足战斗场次才是对冲
     * @param startTime      开始时间
     * @param endTime        结束时间
     */
    List<PlayerAttackStatDO> queryPlayerAttackStat(@Param("factionId") long factionId,
                                                   @Param("windowMinutes") int windowMinutes,
                                                   @Param("minBattleCount") int minBattleCount,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);
}