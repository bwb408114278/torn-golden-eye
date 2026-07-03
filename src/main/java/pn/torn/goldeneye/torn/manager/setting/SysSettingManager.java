package pn.torn.goldeneye.torn.manager.setting;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置公共逻辑层
 *
 * @author Bai
 * @version 1.2.8
 * @since 2025.09.17
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SysSettingManager implements DataCacheManager {
    private final SysSettingDAO settingDao;
    @Lazy
    @Resource
    private SysSettingManager settingManager;

    @Override
    public void warmUpCache() {
        settingManager.getBotId();
    }

    @Override
    @CacheEvict(value = CacheConstants.KEY_SYS_SETTING, allEntries = true)
    public void refreshCache() {
        log.info("系统设置缓存已重置");
    }

    /**
     * 更新数据并清除缓存
     */
    @CacheEvict(cacheNames = CacheConstants.KEY_SYS_SETTING, key = "#settingKey")
    public void updateSetting(String settingKey, String settingValue) {
        settingDao.updateSetting(settingKey, settingValue);
    }

    /**
     * 获取配置值
     *
     * @param settingKey 配置Key
     * @return 配置值
     */
    @Cacheable(value = CacheConstants.KEY_SYS_SETTING, key = "#settingKey")
    public String getSettingValue(String settingKey) {
        return settingDao.querySettingValue(settingKey);
    }

    /**
     * 获取机器人ID
     */
    @Cacheable(value = CacheConstants.KEY_SYS_SETTING_BOT_ID)
    public List<Long> getBotId() {
        String botIds = settingManager.getSettingValue(SettingConstants.KEY_BOT_ID);
        return Arrays.stream(botIds.split(",")).map(Long::parseLong).toList();
    }

    /**
     * 获取股票个性分类配置
     *
     * @return 股票简称 → 个性分类的映射，配置不存在时返回空 Map
     */
    public Map<String, StockPersonalityEnum> getStockPersonalities() {
        String raw = settingManager.getSettingValue(SettingConstants.KEY_STOCK_PERSONALITY);
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, StockPersonalityEnum> result = new HashMap<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2) {
                try {
                    result.put(parts[0].trim().toUpperCase(),
                            StockPersonalityEnum.valueOf(parts[1].trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // 忽略无效的配置项
                }
            }
        }
        return result;
    }
}