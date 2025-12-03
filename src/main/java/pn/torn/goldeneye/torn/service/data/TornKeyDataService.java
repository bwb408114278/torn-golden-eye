package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyDTO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Torn基础数据逻辑层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.10
 */
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_KEY)
public class TornKeyDataService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornApi tornApi;
    private final SysSettingDAO settingDao;
    private final TornApiKeyDAO keyDao;
    private final ProjectProperty projectProperty;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_KEY_DATA_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            spiderUserData();
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

            LocalDate to = LocalDate.now();
            settingDao.updateSetting(SettingConstants.KEY_KEY_DATA_LOAD, DateTimeUtils.convertToString(to));
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
            List<CompletableFuture<Void>> futureList = new ArrayList<>();
            for (TornApiKeyDO key : keyList) {
                futureList.add(CompletableFuture.runAsync(() -> updateKeyByRequest(key), virtualThreadExecutor));
            }

            CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
            keyDao.lambdaUpdate().set(TornApiKeyDO::getUseCount, 0).update();
            apiKeyConfig.reloadKeyData();
        } catch (CompletionException e) {
            throw new BizException("同步Key时出错", e.getCause());
        }
    }

    /**
     * 通过请求更新Key
     */
    private void updateKeyByRequest(TornApiKeyDO oldKey) {
        try {
            TornApiKeyVO resp = tornApi.sendRequest(new TornApiKeyDTO(oldKey.getApiKey()), null, TornApiKeyVO.class);
            TornApiKeyDO newKey = new TornApiKeyDO(oldKey.getQqId(), oldKey.getApiKey(), resp.getInfo());
            if (!oldKey.getFactionId().equals(newKey.getFactionId()) ||
                    !oldKey.getHasFactionAccess().equals(newKey.getHasFactionAccess())) {
                newKey.setId(oldKey.getId());
                keyDao.updateById(newKey);
            }

            Thread.sleep(1000L);
        } catch (BizException e) {
            if (e.getCode() == BotConstants.EX_INVALID_KEY) {
                apiKeyConfig.invalidateKey(oldKey);
            } else {
                apiKeyConfig.returnKey(oldKey);
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("同步Key的等待时间出错", e);
        }
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime execTime) {
        taskService.updateTask("key-data-reload", this::spiderUserData, execTime);
    }
}