package pn.torn.goldeneye.napcat.strategy.faction.attack;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwAttackFrequencySummaryVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowQuery;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.service.faction.attack.RwStatWindowService;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * RW基础策略
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.06.17
 */
@Slf4j
public abstract class BaseRwStrategy extends SmthMsgStrategy {
    @Resource
    private TornFactionRwDAO rwDao;
    @Resource
    private TornAttackLogDAO attackLogDao;
    @Resource
    private ProjectProperty projectProperty;
    @Resource
    private RwStatWindowService rwStatWindowService;
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
        return queryAttackList(rw, queryActiveTimeWindows(rw));
    }

    /**
     * 按指定时间窗口查询战神统计。
     *
     * @param rw      RW对象
     * @param windows 活跃时间窗口
     * @return 玩家攻击统计
     */
    protected List<PlayerAttackStatDO> queryAttackList(TornFactionRwDO rw, List<AttackTimeWindowDO> windows) {
        if (windows.isEmpty()) {
            return Collections.emptyList();
        }
        return attackLogDao.queryPlayerAttackStatByWindows(
                rw.getFactionId(), rw.getOpponentFactionId(), windows);
    }

    /**
     * 解析RW统计指令参数。
     *
     * @param msg 指令参数
     * @return 解析后的RW和窗口查询参数
     * @throws IllegalArgumentException 参数格式错误时抛出
     */
    protected RwStatWindowQuery parseStatWindowQuery(String msg) {
        return RwStatWindowQuery.parse(msg);
    }

    /**
     * 按统计参数定位RW，保持纯数字参数的既有RWID语义。
     *
     * @param sender 消息发送人
     * @param query  统计查询参数
     * @return RW对象
     */
    protected TornFactionRwDO getStatWindowRw(QqRecMsgSender sender, RwStatWindowQuery query) {
        return getCurrentRw(sender, query.rwId() == null ? null : query.rwId().toString());
    }

    /**
     * 按需刷新并查询指定窗口。
     *
     * @param rw         RW对象
     * @param windowCode 窗口字母
     * @return 窗口对象，不存在时返回null
     */
    protected RwStatWindowVO getExplicitStatWindow(TornFactionRwDO rw, String windowCode) {
        refreshStatWindows(rw);
        return rwStatWindowService.queryWindow(rw, windowCode);
    }

    /**
     * 按需刷新并查询默认频率窗口。
     *
     * @param rw RW对象
     * @return 最近已确认窗口，不存在时返回null
     */
    protected RwStatWindowVO getLatestConfirmedStatWindow(TornFactionRwDO rw) {
        refreshStatWindows(rw);
        return rwStatWindowService.queryLatestConfirmedWindow(rw);
    }

    /**
     * 按需刷新并查询RW窗口目录。
     *
     * @param rw RW对象
     * @return 窗口目录
     */
    protected List<RwStatWindowVO> getStatWindowCatalog(TornFactionRwDO rw) {
        refreshStatWindows(rw);
        return rwStatWindowService.queryCatalog(rw);
    }

    /**
     * 刷新统计窗口，隔离派生数据刷新异常。
     *
     * @param rw RW对象
     */
    protected void refreshStatWindows(TornFactionRwDO rw) {
        try {
            rwStatWindowService.refreshWindows(rw);
        } catch (RuntimeException e) {
            log.error("RW对冲窗口刷新失败，rwId={}", rw.getId(), e);
        }
    }

    /**
     * 获取窗口对应的战神时间范围。
     *
     * @param window 窗口对象
     * @return 单一战神统计窗口
     */
    protected AttackTimeWindowDO toAttackTimeWindow(RwStatWindowVO window) {
        return new AttackTimeWindowDO(window.getStartTime(), window.getEndTime());
    }

    /**
     * 查询窗口用户出手统计。
     *
     * @param rw     RW对象
     * @param window 统计窗口
     * @return 双方用户统计摘要
     */
    protected RwAttackFrequencySummaryVO queryFrequency(TornFactionRwDO rw, RwStatWindowVO window) {
        return rwStatWindowService.queryFrequency(rw, window);
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
