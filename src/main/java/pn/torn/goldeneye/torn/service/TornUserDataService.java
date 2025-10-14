package pn.torn.goldeneye.torn.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.member.TornFactionMemberManager;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyDTO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn基础数据逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.10
 */
@Service
@RequiredArgsConstructor
@Order(10001)
public class TornUserDataService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornApi tornApi;
    private final TornFactionMemberManager factionMemberManager;
    private final SysSettingDAO settingDao;
    private final TornApiKeyDAO keyDao;
    private final TornSettingFactionDAO settingFactionDao;
    private final ProjectProperty projectProperty;

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_USER_DATA_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 1, 0);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(this::spiderUserData);
        } else {
            addScheduleTask(from.plusDays(1));
        }
    }

    /**
     * 爬取用户数据
     */
    public void spiderUserData() {
        try {
            spiderKey();
            spiderFactionMember();

            LocalDate to = LocalDate.now();
            settingDao.updateSetting(SettingConstants.KEY_USER_DATA_LOAD, DateTimeUtils.convertToString(to));
            addScheduleTask(to.plusDays(1).atTime(8, 1, 0));
        } catch (Exception e) {
            // 失败2分钟后重试
            addScheduleTask(LocalDateTime.now().plusMinutes(2));
        }
    }

    /**
     * 爬取Key
     */
    public void spiderKey() {
        try {
            List<TornApiKeyDO> keyList = keyDao.list();
            for (TornApiKeyDO oldKey : keyList) {
                TornApiKeyVO resp = tornApi.sendRequest(new TornApiKeyDTO(oldKey.getApiKey()), null, TornApiKeyVO.class);
                TornApiKeyDO newKey = new TornApiKeyDO(oldKey.getUserId(), oldKey.getApiKey(), resp.getInfo());
                if (!oldKey.getFactionId().equals(newKey.getFactionId()) ||
                        !oldKey.getHasFactionAccess().equals(newKey.getHasFactionAccess())) {
                    newKey.setId(oldKey.getId());
                    keyDao.updateById(newKey);
                }

                Thread.sleep(1000L);
            }

            keyDao.lambdaUpdate().set(TornApiKeyDO::getUseCount, 0).update();
            apiKeyConfig.reloadKeyData();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("同步帮派人员的等待时间出错", e);
        }
    }

    /**
     * 爬取帮派成员
     */
    public void spiderFactionMember() {
        try {
            List<TornSettingFactionDO> factionList = settingFactionDao.list();
            for (TornSettingFactionDO faction : factionList) {
                TornFactionMemberDTO param = new TornFactionMemberDTO(faction.getId());
                TornFactionMemberListVO memberList = tornApi.sendRequest(param, TornFactionMemberListVO.class);
                factionMemberManager.updateFactionMember(faction.getId(), memberList);
                Thread.sleep(1000L);
            }

            LocalDate to = LocalDate.now();
            settingDao.updateSetting(SettingConstants.KEY_BASE_DATA_LOAD, DateTimeUtils.convertToString(to));
            addScheduleTask(to.plusDays(1).atTime(8, 1, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("同步帮派人员的等待时间出错", e);
        }
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime execTime) {
        taskService.updateTask("user-data-reload", this::spiderUserData, execTime);
    }
}