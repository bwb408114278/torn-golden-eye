package pn.torn.goldeneye.torn.service.data;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.attack.TornRwReviveManager;
import pn.torn.goldeneye.torn.manager.faction.attack.TornRwWarningManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwDTO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwRespVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwVO;
import pn.torn.goldeneye.torn.service.faction.attack.TornFactionAttackService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

/**
 * TornRw数据逻辑层
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.12.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_RW)
public class TornRwDataService {
    private final DynamicTaskService taskService;
    private final TornApi tornApi;
    private final TornFactionAttackService attackService;
    private final TornRwWarningManager rwWarningManager;
    private final TornSettingFactionManager settingFactionManager;
    private final SysSettingDAO settingDao;
    private final TornFactionRwDAO rwDao;
    private final TornRwReviveManager reviveManager;
    private final ProjectProperty projectProperty;

    private static final long QUERY_OVERLAP_SECONDS = 2L;
    private static final long NORMAL_INTERVAL_MINUTES = 2;
    private static final long LOW_FREQUENCY_INTERVAL_MINUTES = 60;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        List<TornFactionRwDO> currentRwList = rwDao.lambdaQuery().isNull(TornFactionRwDO::getEndTime).list();
        if (CollectionUtils.isEmpty(currentRwList)) {
            return;
        }

        for (TornFactionRwDO rw : currentRwList) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(rw.getFactionId());
            String value = settingDao.querySettingValue(faction.getFactionShortName() + "_" + SettingConstants.KEY_RW_LOAD);
            LocalDateTime from = DateTimeUtils.convertToDateTime(value);
            long intervalMinutes = getIntervalMinutes(LocalDateTime.now(), rw);

            if (LocalDateTime.now().minusMinutes(intervalMinutes).isAfter(from)) {
                spiderRwData(faction, from);
            } else {
                addScheduleTask(faction, from, rw);
            }
        }
    }

    /**
     * 爬取RW数据
     */
    public void spiderRwData(TornSettingFactionDO faction, LocalDateTime from) {
        TornFactionRwRespVO resp = tornApi.sendRequest(faction.getId(), new TornFactionRwDTO(), TornFactionRwRespVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getRwList())) {
            throw new BizException("未查询到RW数据");
        }

        TornFactionRwVO currentRw = resp.getRwList().getFirst();
        spiderRwData(currentRw, faction, from);
    }

    /**
     * 爬取RW数据
     */
    public void spiderRwData(TornFactionRwVO currentRw, TornSettingFactionDO faction, LocalDateTime from) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = DateTimeUtils.convertToDateTime(currentRw.getStart());
        TornFactionRwDO rw = rwDao.getById(currentRw.getId());
        boolean ended = currentRw.getEnd() != 0L;

        LocalDateTime to = buildQueryTo(currentRw, ended, start, now);
        if (!now.isAfter(start)) {
            addScheduleTask(faction, to, rw);
            return;
        }
        LocalDateTime queryFrom = buildQueryFromWithOverlap(from, start);
        if (invalidTimeRange(faction, rw, from, to, queryFrom, ended)) {
            return;
        }

        try {
            Collection<TornFactionMemberVO> memberList = attackService.spiderAttackData(faction,
                    currentRw.getOpponentFaction(faction.getId()).getId(), queryFrom, to);
            updateRwLoadCheckpoint(faction, to);
            reviveManager.spiderReviveData(faction, rw, start, to);
            if (!ended && !isLowFrequencyPeriod(now, rw)) {
                rwWarningManager.sendWarning(rw, now, memberList);
            }

            if (ended) {
                rwDao.lambdaUpdate()
                        .set(TornFactionRwDO::getEndTime, to)
                        .eq(TornFactionRwDO::getId, rw.getId())
                        .update();
            } else {
                addScheduleTask(faction, to, rw);
            }
        } catch (Exception e) {
            log.error("RW抓取失败，保留旧checkpoint重试。faction={}, rwId={}, from={}, to={}",
                    faction.getFactionShortName(), rw.getId(), from, to, e);
            addScheduleTask(faction, from, rw);
            throw e;
        }
    }

    private LocalDateTime buildQueryTo(TornFactionRwVO currentRw, boolean ended, LocalDateTime start, LocalDateTime now) {
        if (ended) {
            return DateTimeUtils.convertToDateTime(currentRw.getEnd());
        }
        return now.isAfter(start) ? now : start.plusMinutes(1);
    }

    private LocalDateTime buildQueryFromWithOverlap(LocalDateTime from, LocalDateTime start) {
        LocalDateTime result = from.minusSeconds(QUERY_OVERLAP_SECONDS);
        return result.isBefore(start) ? start : result;
    }

    private boolean invalidTimeRange(TornSettingFactionDO faction, TornFactionRwDO rw, LocalDateTime from, LocalDateTime to, LocalDateTime queryFrom, boolean ended) {
        if (ObjectUtils.isEmpty(rw)) {
            log.warn("RW数据不存在, faction={}, from={}, to={}", faction.getFactionShortName(), from, to);
            return true;
        }
        return !queryFrom.isBefore(to) && !queryFrom.isEqual(to) || (!ended && from.isAfter(to));
    }

    private void updateRwLoadCheckpoint(TornSettingFactionDO faction, LocalDateTime to) {
        settingDao.updateSetting(faction.getFactionShortName() + "_" + SettingConstants.KEY_RW_LOAD,
                DateTimeUtils.convertToString(to));
    }

    /**
     * 添加定时任务
     */
    public void addScheduleTask(TornSettingFactionDO faction, LocalDateTime from, TornFactionRwDO rw) {
        LocalDateTime nextExecutionTime = calculateNextExecutionTime(from, rw);
        taskService.updateTask(faction.getFactionShortName() + "-" + "rw-data-reload",
                () -> this.spiderRwData(faction, from),
                nextExecutionTime);
    }

    /**
     * 判断是否在低频时段（0点到8点）
     */
    private boolean isLowFrequencyPeriod(LocalDateTime dateTime, TornFactionRwDO rw) {
        LocalTime time = dateTime.toLocalTime();
        LocalTime start = rw.getDisbandTime();
        LocalTime end = rw.getGatheringTime();
        if (!start.isBefore(end)) {
            return !time.isBefore(start) || time.isBefore(end);
        }
        return !time.isBefore(start) && time.isBefore(end);
    }

    /**
     * 根据当前时间获取抓取间隔
     */
    private long getIntervalMinutes(LocalDateTime dateTime, TornFactionRwDO rw) {
        return isLowFrequencyPeriod(dateTime, rw) ? LOW_FREQUENCY_INTERVAL_MINUTES : NORMAL_INTERVAL_MINUTES;
    }

    /**
     * 计算下次执行时间
     */
    private LocalDateTime calculateNextExecutionTime(LocalDateTime currentTime, TornFactionRwDO rw) {
        long intervalMinutes = getIntervalMinutes(currentTime, rw);
        return currentTime.plusMinutes(intervalMinutes);
    }


}
