package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackItemDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.repository.model.torn.PlayerDefendStatDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn战斗日志数据库访问层
 *
 * @author Bai
 * @version 1.3.5
 * @since 2025.12.18
 */
@Mapper
public interface TornAttackLogMapper extends BaseMapper<TornAttackLogDO> {
    /**
     * 查询活跃对战时间窗口（滚动窗口：windowMinutes分钟内双方攻击次数>=minBattleCount的连续时间段）
     *
     * @param factionId         帮派ID
     * @param opponentFactionId 对手帮派ID
     * @param windowMinutes     时间窗口长度（分钟）
     * @param minBattleCount    满足战斗场次才是对冲
     * @param startTime         开始时间
     * @param endTime           结束时间
     */
    List<AttackTimeWindowDO> queryActiveTimeWindows(@Param("factionId") long factionId,
                                                    @Param("opponentFactionId") long opponentFactionId,
                                                    @Param("windowMinutes") int windowMinutes,
                                                    @Param("minBattleCount") int minBattleCount,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);

    List<PlayerAttackStatDO> queryPlayerAttackStatByWindows(@Param("factionId") long factionId,
                                                            @Param("opponentFactionId") long opponentFactionId,
                                                            @Param("windows") List<AttackTimeWindowDO> windows);

    /**
     * 统计指定时间的物品数据
     *
     * @param factionId 帮派ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     */
    List<PlayerAttackItemDO> queryPlayerAttackItem(@Param("factionId") long factionId,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定时间的被爆头数据
     *
     * @param factionId 帮派ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     */
    List<PlayerDefendStatDO> queryPlayerHeadHit(@Param("factionId") long factionId,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /**
     * 冲突安全批量写入攻击日志
     * <p>
     * 以有效事实部分唯一索引(attacker_id, defender_id, log_time, log_text, log_action)
     * WHERE deleted = 0 为ON CONFLICT目标: 冲突行仅跳过自身(DO NOTHING),
     * 不影响批次内其他新事实写入; NOT NULL、类型/长度等非唯一冲突类数据库错误仍正常抛出。
     *
     * @param logList 待写入日志列表, 调用前须保证每条记录主键已由DAO补齐
     * @return 实际插入行数, 有效事实冲突跳过的行不计入
     */
    int insertIgnoreConflict(@Param("logList") List<TornAttackLogDO> logList);
}