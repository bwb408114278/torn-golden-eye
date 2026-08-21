package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
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
 * @version 1.3.8
 * @since 2025.12.18
 */
@Repository
public class TornAttackLogDAO extends ServiceImpl<TornAttackLogMapper, TornAttackLogDO> {
    /**
     * 统计指定时间窗口的玩家数据（基于已计算的时间窗口列表，不重复滑动窗口计算）
     *
     * @param factionId         帮派ID
     * @param opponentFactionId 对手帮派ID
     * @param windows           活跃时间窗口列表
     */
    public List<PlayerAttackStatDO> queryPlayerAttackStatByWindows(long factionId, long opponentFactionId,
                                                                   List<AttackTimeWindowDO> windows) {
        return baseMapper.queryPlayerAttackStatByWindows(factionId, opponentFactionId, windows);
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

    /**
     * 冲突安全批量写入攻击日志
     * <p>
     * 有效攻击日志事实以(attacker_id, defender_id, log_time, log_text, log_action, source_occurrence)
     * 六字段为唯一键, 其中sourceOccurrence由采集服务按同一来源API日志流内相同五字段事实的
     * 返回顺序编号; 冲突行仅跳过自身, 数据库非唯一冲突类异常正常抛出。自定义XML不经过MyBatis-Plus
     * 主键自动填充, 缺失ID的记录在此统一以雪花ID补齐, 此处是XML写入主键补齐的唯一位置。
     *
     * @param logList 待写入日志列表, 空集合直接返回
     * @return 实际插入行数, 有效事实冲突跳过的行不计入
     */
    public int insertIgnoreConflict(List<TornAttackLogDO> logList) {
        if (CollectionUtils.isEmpty(logList)) {
            return 0;
        }
        logList.stream()
                .filter(log -> log.getId() == null)
                .forEach(log -> log.setId(IdWorker.getId()));
        return baseMapper.insertIgnoreConflict(logList);
    }
}