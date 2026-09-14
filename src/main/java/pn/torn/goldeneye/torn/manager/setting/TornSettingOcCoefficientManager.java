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
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcCoefficientDO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * OC系数设置公共逻辑层
 *
 * @author Bai
 * @version 1.6.2
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
    public BigDecimal getCoefficient(TornFactionOcDO oc, String position, int passRate) {
        List<TornSettingOcCoefficientDO> list = coefficientManager.getList();
        long scopeFactionId = resolveScopeFactionId(list, oc.getFactionId());
        TornSettingOcCoefficientDO coefficient = getCoefficient(list, scopeFactionId, oc, position, passRate);
        return coefficient != null ? coefficient.getCoefficient() : BigDecimal.ZERO;
    }

    /**
     * 校验指定帮派OC的岗位系数完整性，返回缺失岗位系数的岗位编码清单。
     *
     * <p>解析口径与{@link #getCoefficient}完全一致：帮派在系数表存在任意自有行时仅在该帮派
     * 自有行内查找，否则回落{@code faction_id=0}。要求每个岗位至少存在一条系数行（不限成功率区间），
     * 返回空列表表示完整。CCRC等仅有部分OC自有行的帮派，新增无自有行OC时会得到全部岗位缺失，
     * 调用方必须按不完整拒绝。</p>
     *
     * @param factionId 帮派ID
     * @param ocName    OC名称
     * @param rank      OC级别
     * @param slotCodes 岗位编码列表
     * @return 缺少系数行的岗位编码列表，为空表示系数完整
     */
    public List<String> hasCompleteCoefficients(long factionId, String ocName, int rank, List<String> slotCodes) {
        List<TornSettingOcCoefficientDO> list = coefficientManager.getList();
        long scopeFactionId = resolveScopeFactionId(list, factionId);
        List<String> missingSlotCodes = new ArrayList<>();
        for (String slotCode : slotCodes) {
            boolean present = list.stream()
                    .filter(s -> s.getFactionId() != null && s.getFactionId() == scopeFactionId)
                    .filter(s -> s.getOcName().equals(ocName))
                    .filter(s -> s.getRank() != null && s.getRank() == rank)
                    .anyMatch(s -> s.getSlotCode().equals(slotCode));
            if (!present) {
                missingSlotCodes.add(slotCode);
            }
        }
        return List.copyOf(missingSlotCodes);
    }

    /**
     * 解析系数查找的帮派范围：帮派存在任意自有行时仅查自有行，否则回落faction_id=0。
     *
     * @param list      系数配置列表
     * @param factionId 帮派ID
     * @return 实际查找的帮派ID
     */
    private long resolveScopeFactionId(List<TornSettingOcCoefficientDO> list, Long factionId) {
        long target = factionId == null ? 0L : factionId;
        return list.stream().anyMatch(l -> l.getFactionId() != null && l.getFactionId() == target)
                ? target : 0L;
    }

    /**
     * 获取工时系数
     */
    private TornSettingOcCoefficientDO getCoefficient(List<TornSettingOcCoefficientDO> list, long factionId,
                                                      TornFactionOcDO oc, String position, int passRate) {
        return list.stream()
                .filter(s -> s.getFactionId().equals(factionId))
                .filter(s -> s.getOcName().equals(oc.getName()))
                .filter(s -> s.getRank().equals(oc.getRank()))
                .filter(s -> s.getSlotCode().equals(position))
                .filter(s -> s.getPassRateMin() < passRate)
                .filter(s -> s.getPassRateMax() >= passRate)
                .findAny().orElse(null);
    }
}
