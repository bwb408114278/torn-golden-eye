package pn.torn.goldeneye.torn.service.faction.oc;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcUserManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcDTO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Torn Oc用户逻辑层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.20
 */
@Service
@RequiredArgsConstructor
@Order(10004)
public class TornFactionOcUserService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornFactionOcUserManager ocUserManager;
    private final SysSettingDAO settingDao;

    @PostConstruct
    public void init() {
        String value = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_PASS_RATE_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(() -> spiderOcPassRate(to));
        }

        addScheduleTask(to);
    }

    /**
     * 爬取物品使用记录
     */
    public void spiderOcPassRate(LocalDateTime to) {
        List<TornApiKeyDO> keyList = apiKeyConfig.getEnableKeyList();
        for (TornApiKeyDO key : keyList) {
            updateOcRate(key);
        }

        settingDao.updateSetting(TornConstants.SETTING_KEY_OC_PASS_RATE_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 更新OC成功率
     */
    public void updateOcRate(TornApiKeyDO key) {
        List<TornFactionCrimeVO> ocList = new ArrayList<>();
        if (Boolean.TRUE.equals(key.getHasFactionAccess())) {
            TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(), key, TornFactionOcVO.class);
            ocList.addAll(oc.getCrimes());
        } else {
            TornUserOcVO oc = tornApi.sendRequest(new TornUserOcDTO(), key, TornUserOcVO.class);
            if (oc == null || oc.getOrganizedCrime() == null ||
                    CollectionUtils.isEmpty(oc.getOrganizedCrime().getSlots())) {
                return;
            }
            ocList.add(oc.getOrganizedCrime());
        }

        ocUserManager.updateEmptyUserPassRate(key.getUserId(), ocList);
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("oc-pass-rate-reload",
                () -> spiderOcPassRate(to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(5L));
    }
}