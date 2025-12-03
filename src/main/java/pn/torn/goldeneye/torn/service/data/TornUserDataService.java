package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserBsSnapshotDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcUserManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.model.user.bs.TornUserBsDTO;
import pn.torn.goldeneye.torn.model.user.bs.TornUserBsVO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcDTO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Torn 用户数据逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.20
 */
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_USER_DATA)
public class TornUserDataService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornFactionOcUserManager ocUserManager;
    private final TornUserBsSnapshotDAO bsSnapshotDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_USER_DATA_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            spiderAllData(to);
        }

        addScheduleTask(to);
    }

    /**
     * 爬取所有用户数据
     */
    public void spiderAllData(LocalDateTime to) {
        List<TornApiKeyDO> keyList = apiKeyConfig.getAllEnableKeys();
        List<TornUserBsSnapshotDO> existsList = bsSnapshotDao.lambdaQuery()
                .eq(TornUserBsSnapshotDO::getRecordDate, to.toLocalDate())
                .list();

        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (TornApiKeyDO key : keyList) {
            futureList.add(CompletableFuture.runAsync(() -> spiderData(key, existsList), virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        settingDao.updateSetting(SettingConstants.KEY_USER_DATA_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 爬取单条数据
     */
    public void spiderData(TornApiKeyDO key, List<TornUserBsSnapshotDO> snapshotList) {
        updateBsSnapshot(key, snapshotList);
        updateOcRate(key);
    }

    /**
     * 更新用户BS
     */
    private void updateBsSnapshot(TornApiKeyDO key, List<TornUserBsSnapshotDO> snapshotList) {
        LocalDate now = LocalDate.now();
        boolean isExists = snapshotList.stream().anyMatch(s ->
                s.getUserId().equals(key.getUserId()) && s.getRecordDate().equals(now));
        if (isExists) {
            return;
        }

        TornUserBsVO bs = tornApi.sendRequest(new TornUserBsDTO(), key, TornUserBsVO.class);
        TornUserBsSnapshotDO bsSnapshot = bs.getBattleStats().convert2DO(key.getUserId(), now);
        bsSnapshotDao.save(bsSnapshot);
    }

    /**
     * 更新OC成功率
     */
    private void updateOcRate(TornApiKeyDO key) {
        List<TornFactionCrimeVO> ocList = new ArrayList<>();
        if (Boolean.TRUE.equals(key.getHasFactionAccess())) {
            TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(1, false),
                    key, TornFactionOcVO.class);
            ocList.addAll(oc.getCrimes());
        } else {
            TornUserOcVO oc = tornApi.sendRequest(new TornUserOcDTO(), key, TornUserOcVO.class);
            if (oc == null || oc.getOrganizedCrime() == null ||
                    CollectionUtils.isEmpty(oc.getOrganizedCrime().getSlots())) {
                return;
            }
            ocList.add(oc.getOrganizedCrime());
        }

        ocUserManager.updateEmptyUserPassRate(key.getFactionId(), key.getUserId(), ocList);
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("user-data-reload",
                () -> spiderAllData(to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(5L));
    }
}