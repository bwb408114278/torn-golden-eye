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
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcCoefficientDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcCoefficientDO;

import java.math.BigDecimal;
import java.util.List;

/**
 * OC系数设置公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingOcCoefficientManager implements DataCacheManager {
    private final TornSettingOcCoefficientDAO settingOcCoefficientDao;
    @Lazy
    @Resource
    private TornSettingOcCoefficientManager coefficientManager;

    @Override
    public void warmUpCache() {
        coefficientManager.getList();
    }

    @Override
    @CacheEvict(value = CacheConstants.KEY_TORN_SETTING_OC_COEFFICIENT, allEntries = true)
    public void refreshCache() {
        log.info("OC系数设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_COEFFICIENT)
    public List<TornSettingOcCoefficientDO> getList() {
        return settingOcCoefficientDao.list();
    }

    /**
     * 获取工时系数
     */
    public BigDecimal getCoefficient(String ocName, int rank, String position, int passRate) {
        TornSettingOcCoefficientDO coefficient = coefficientManager.getList().stream()
                .filter(s -> s.getOcName().equals(ocName))
                .filter(s -> s.getRank().equals(rank))
                .filter(s -> s.getSlotCode().equals(position))
                .filter(s -> s.getPassRateMin() <= passRate)
                .filter(s -> s.getPassRateMax() >= passRate)
                .findAny().orElse(null);
        return coefficient != null ? coefficient.getCoefficient() : BigDecimal.ONE;
    }
}