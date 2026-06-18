package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornAttackLogMapper;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackItemDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.repository.model.torn.PlayerDefendStatDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn战斗日志持久层类
 *
 * @author Bai
 * @version 1.1.4
 * @since 2025.12.18
 */
@Repository
public class TornAttackLogDAO extends ServiceImpl<TornAttackLogMapper, TornAttackLogDO> {
    /**
     * 统计指定时间窗口的玩家数据
     *
     * @param factionId         帮派ID
     * @param opponentFactionId 对手帮派ID
     * @param windowMinutes     时间窗口长度
     * @param minBattleCount    满足战斗场次才是对冲
     * @param startTime         开始时间
     * @param endTime           结束时间
     */
    public List<PlayerAttackStatDO> queryPlayerAttackStat(long factionId, long opponentFactionId,
                                                          int windowMinutes, int minBattleCount,
                                                          LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.queryPlayerAttackStat(factionId, opponentFactionId, windowMinutes, minBattleCount, startTime, endTime);
    }

    /**
     * 查询活跃对战时间窗口（滚动窗口：windowMinutes分钟内双方攻击次数>=minBattleCount的连续时间段）
     *
     * @param factionId         帮派ID
     * @param opponentFactionId 对手帮派ID
     * @param windowMinutes     时间窗口长度（分钟）
     * @param minBattleCount    满足战斗场次才是对冲
     * @param startTime         开始时间
     * @param endTime           结束时间
     * @return 活跃对战时间窗口列表
     */
    public List<AttackTimeWindowDO> queryActiveTimeWindows(long factionId, long opponentFactionId,
                                                           int windowMinutes, int minBattleCount,
                                                           LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.queryActiveTimeWindows(factionId, opponentFactionId, windowMinutes, minBattleCount, startTime, endTime);
    }

    /**
     * 统计指定时间的物品数据
     *
     * @param factionId 帮派ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     */
    public List<PlayerAttackItemDO> queryPlayerAttackItem(long factionId, LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.queryPlayerAttackItem(factionId, startTime, endTime);
    }

    /**
     * 统计指定时间的被爆头数据
     *
     * @param factionId 帮派ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     */
    public List<PlayerDefendStatDO> queryPlayerHeadHit(long factionId, LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.queryPlayerHeadHit(factionId, startTime, endTime);
    }
}