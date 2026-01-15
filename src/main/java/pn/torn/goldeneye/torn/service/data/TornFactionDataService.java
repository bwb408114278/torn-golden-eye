package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.member.TornFactionMemberManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.ce.TornFactionCeRankDTO;
import pn.torn.goldeneye.torn.model.faction.ce.TornFactionCeRankVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Torn帮派数据逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.12.03
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_FACTION_DATA)
public class TornFactionDataService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornApi tornApi;
    private final TornFactionMemberManager factionMemberManager;
    private final TornSettingFactionManager settingFactionManager;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_FACTION_DATA_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 10, 0);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            spiderFactionData();
        } else {
            addScheduleTask(from.plusDays(1));
        }
    }

    /**
     * 爬取帮派数据
     */
    public void spiderFactionData() {
        List<TornSettingFactionDO> factionList = settingFactionManager.getList();
        List<CompletableFuture<Void>> futureList = new ArrayList<>();

        for (TornSettingFactionDO faction : factionList) {
            futureList.add(CompletableFuture.runAsync(() -> {
                long factionId = faction.getId();
                TornApiKeyDO key = apiKeyConfig.getFactionKey(factionId, true);
                if (key == null) {
                    return;
                } else {
                    apiKeyConfig.returnKey(key);
                }

                spiderFactionMember(factionId);
                spiderFactionCeRank(factionId);
            }, virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();

        LocalDate to = LocalDate.now();
        settingDao.updateSetting(SettingConstants.KEY_FACTION_DATA_LOAD, DateTimeUtils.convertToString(to));
        addScheduleTask(to.plusDays(1).atTime(8, 10, 0));
    }

    /**
     * 爬取帮派成员
     */
    public void spiderFactionMember(long factionId) {
        TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
        TornFactionMemberListVO memberList = tornApi.sendRequest(param, TornFactionMemberListVO.class);
        if (memberList == null || CollectionUtils.isEmpty(memberList.getMembers())) {
            throw new BizException("同步帮派成员出错");
        }

        factionMemberManager.updateFactionMember(factionId, memberList);
    }

    /**
     * 爬取帮派CE排名
     */
    public void spiderFactionCeRank(long factionId) {
        TornFactionCeRankDTO param = new TornFactionCeRankDTO();
        TornFactionCeRankVO resp = tornApi.sendRequest(factionId, param, TornFactionCeRankVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getCrimeExpList())) {
            throw new BizException("同步帮派CE排名出错");
        }

        for (int i = 0; i < resp.getCrimeExpList().size(); i++) {
            userDao.lambdaUpdate()
                    .set(TornUserDO::getCrimeExpRank, i + 1)
                    .eq(TornUserDO::getId, resp.getCrimeExpList().get(i))
                    .update();
        }
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime execTime) {
        taskService.updateTask("faction-data-reload", this::spiderFactionData, execTime);
    }
}