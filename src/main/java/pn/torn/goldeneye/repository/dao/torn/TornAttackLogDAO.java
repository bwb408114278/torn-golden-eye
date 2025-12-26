package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornAttackLogMapper;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn战斗日志持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Repository
public class TornAttackLogDAO extends ServiceImpl<TornAttackLogMapper, TornAttackLogDO> {
    /**
     * 统计指定时间窗口的玩家数据
     *
     * @param factionId      帮派ID
     * @param windowMinutes  时间窗口长度
     * @param minBattleCount 满足战斗场次才是对冲
     * @param startTime      开始时间
     * @param endTime        结束时间
     */
    public List<PlayerAttackStatDO> queryPlayerAttackStat(long factionId, int windowMinutes, int minBattleCount,
                                                          LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.queryPlayerAttackStat(factionId, windowMinutes, minBattleCount, startTime, endTime);
    }
}