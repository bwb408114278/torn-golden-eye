package pn.torn.goldeneye.napcat.strategy.faction.attack;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * RW基础策略
 *
 * @author Bai
 * @version 1.2.6
 * @since 2026.06.17
 */
public abstract class BaseRwStrategy extends SmthMsgStrategy {
    @Resource
    private TornFactionRwDAO rwDao;
    @Resource
    private TornAttackLogDAO attackLogDao;
    @Resource
    private ProjectProperty projectProperty;
    private static final int WINDOW_MINUTES = 3;
    private static final int MIN_BATTLE_COUNT = 100;

    @Override
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId(),
                BotConstants.GROUP_CCRC_ID,
                BotConstants.GROUP_SH_ID,
                BotConstants.GROUP_HP_ID,
                BotConstants.GROUP_BSU_ID);
    }

    /**
     * 查询对冲战斗记录
     * <p>
     * 复用 queryActiveTimeWindows 获取活跃时间窗口，
     * 再基于窗口列表查询玩家统计数据，避免重复计算滑动窗口。
     */
    protected List<PlayerAttackStatDO> queryAttackList(TornFactionRwDO rw) {
        List<AttackTimeWindowDO> windows = queryActiveTimeWindows(rw);
        if (windows.isEmpty()) {
            return Collections.emptyList();
        }
        return attackLogDao.queryPlayerAttackStatByWindows(
                rw.getFactionId(), rw.getOpponentFactionId(), windows);
    }

    /**
     * 获取当前/指定RW
     */
    protected TornFactionRwDO getCurrentRw(QqRecMsgSender sender, String msg) {
        long factionId = getTornFactionIdBySender(sender);
        return getCurrentRw(factionId, msg);
    }

    /**
     * 获取当前/指定RW
     */
    protected TornFactionRwDO getCurrentRw(long factionId, String msg) {
        long rwId = 0L;
        if (NumberUtils.isLong(msg)) {
            rwId = Long.parseLong(msg);
        }

        Page<TornFactionRwDO> rwList = rwDao.lambdaQuery()
                .eq(TornFactionRwDO::getFactionId, factionId)
                .eq(rwId > 0L, TornFactionRwDO::getId, rwId)
                .le(rwId == 0L, TornFactionRwDO::getStartTime, LocalDateTime.now())
                .orderByDesc(TornFactionRwDO::getStartTime)
                .page(new Page<>(1, 1));
        return rwList.getRecords().isEmpty() ? null : rwList.getRecords().getFirst();
    }

    /**
     * 查询活跃对战时间窗口（滚动窗口：windowMinutes分钟内双方攻击次数>=minBattleCount的连续时间段）
     * <p>
     * 用于神医榜等需要根据实际战斗活跃时段过滤数据的场景
     *
     * @param rw RW对象
     * @return 活跃对战时间窗口列表
     */
    protected List<AttackTimeWindowDO> queryActiveTimeWindows(TornFactionRwDO rw) {
        LocalDateTime startTime = rw.getStartTime();
        LocalDateTime endTime = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        return attackLogDao.queryActiveTimeWindows(rw.getFactionId(),
                rw.getOpponentFactionId(), WINDOW_MINUTES, MIN_BATTLE_COUNT, startTime, endTime);
    }
}
