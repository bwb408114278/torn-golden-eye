package pn.torn.goldeneye.napcat.strategy.faction.attack;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RW基础策略
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
public abstract class BaseRwStrategy extends SmthMsgStrategy {
    @Resource
    private TornFactionRwDAO rwDao;
    @Resource
    private TornAttackLogDAO attackLogDao;
    @Resource
    private ProjectProperty projectProperty;

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
     */
    protected List<PlayerAttackStatDO> queryAttackList(TornFactionRwDO rw) {
        int windowMinutes = 3;
        int minBattleCount = 100;
        LocalDateTime startTime = rw.getStartTime();
        LocalDateTime endTime = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        return attackLogDao.queryPlayerAttackStat(rw.getFactionId(),
                rw.getOpponentFactionId(), windowMinutes, minBattleCount, startTime, endTime);
    }

    /**
     * 获取当前/指定RW
     */
    protected TornFactionRwDO getCurrentRw(QqRecMsgSender sender, String msg) {
        long factionId = getTornFactionIdBySender(sender);
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
     * 计算对冲时间窗口列表
     */
    protected List<TimeWindow> buildAttackTimeWindowList(TornFactionRwDO rw) {
        List<TimeWindow> windows = new ArrayList<>();
        LocalDateTime current = rw.getStartTime();
        LocalDateTime actualEnd = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        while (current.isBefore(actualEnd)) {
            LocalDateTime windowStart = current.with(rw.getGatheringTime());
            LocalDateTime windowEnd = current.with(rw.getDisbandTime());
            if (!windowEnd.isAfter(windowStart)) {
                windowEnd = windowEnd.plusDays(1);
            }
            LocalDateTime boundedStart = windowStart.isBefore(rw.getStartTime()) ? rw.getStartTime() : windowStart;
            LocalDateTime boundedEnd = windowEnd.isAfter(actualEnd) ? actualEnd : windowEnd;
            if (boundedStart.isBefore(boundedEnd)) {
                windows.add(new TimeWindow(boundedStart, boundedEnd));
            }
            current = current.plusDays(1);
        }
        return windows;
    }

    protected record TimeWindow(LocalDateTime start, LocalDateTime end) {
        public boolean contains(LocalDateTime dateTime) {
            return !dateTime.isBefore(start) && dateTime.isBefore(end);
        }
    }
}
