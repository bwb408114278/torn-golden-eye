package pn.torn.goldeneye.torn.service.faction.armory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionGiveFundsManager;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionItemUsedManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 帮派新闻记录逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_FACTION_NEWS)
@Slf4j
public class FactionNewsService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornSettingFactionManager settingFactionManager;
    private final FactionItemUsedManager itemUsedManager;
    private final FactionGiveFundsManager giveFundsManager;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_NEWS_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            spiderNewsData(from, to);
        }

        addScheduleTask(to);
    }

    /**
     * 爬取新闻记录
     */
    public void spiderNewsData(LocalDateTime from, LocalDateTime to) {
        List<TornSettingFactionDO> factionList = settingFactionManager.getList();
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (TornSettingFactionDO faction : factionList) {
            futureList.add(CompletableFuture.runAsync(() -> {
                        TornApiKeyDO key = apiKeyConfig.getFactionKey(faction.getId(), true);
                        if (key == null) {
                            return;
                        }

                        apiKeyConfig.returnKey(key);
                        itemUsedManager.spiderItemUseData(faction, from, to);
                        giveFundsManager.spiderGiveFundsData(faction, from, to);
                    },
                    virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        settingDao.updateSetting(SettingConstants.KEY_NEWS_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("faction-news-reload",
                () -> spiderNewsData(to.plusSeconds(1), to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(15));
    }
}