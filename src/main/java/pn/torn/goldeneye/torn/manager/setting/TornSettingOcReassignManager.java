package pn.torn.goldeneye.torn.manager.setting;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.FactionOcExclusion;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 大锅饭配置唯一派生门面。
 *
 * <p>大锅饭名单、普通收益排除规则、大锅饭帮派集合、补算扫描起点与收益模式全部由本类从
 * 两张配置表的缓存列表派生，禁止在其他位置复制派生逻辑。列表加载挂Caffeine缓存，
 * 写侧事务提交后通过{@link #refreshCache()}驱逐。{@link #resolveIncomeStartTime}保持纯函数语义：
 * 仅依赖已缓存列表与入参，不查库、不读系统时间。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingOcReassignManager implements DataCacheManager {
    private final TornSettingOcReassignFactionDAO reassignFactionDao;
    private final TornSettingOcReassignOcDAO reassignOcDao;
    @Lazy
    @Resource
    private TornSettingOcReassignManager reassignManager;

    @Override
    public void warmUpCache() {
        reassignManager.getFactionList();
        reassignManager.getOcList();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_OC_REASSIGN_FACTION, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_OC_REASSIGN_OC, allEntries = true)})
    public void refreshCache() {
        log.info("大锅饭配置缓存已重置");
    }

    /**
     * 查询未删除的大锅饭帮派开关列表。
     *
     * @return 帮派开关配置列表
     */
    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_REASSIGN_FACTION)
    public List<TornSettingOcReassignFactionDO> getFactionList() {
        return reassignFactionDao.lambdaQuery()
                .eq(TornSettingOcReassignFactionDO::getDeleted, 0)
                .list();
    }

    /**
     * 查询未删除的大锅饭OC范围行列表。
     *
     * @return 范围行配置列表
     */
    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_REASSIGN_OC)
    public List<TornSettingOcReassignOcDO> getOcList() {
        return reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getDeleted, 0)
                .list();
    }

    /**
     * 获取指定帮派的大锅饭OC名单。
     *
     * <p>派生规则：帮派开关启用且范围行启用的oc_name列表；帮派未启用时返回空列表。</p>
     *
     * @param factionId 帮派ID
     * @return 大锅饭OC名单
     */
    public List<String> getRotationOcNames(long factionId) {
        if (!enabledFactionIds().contains(factionId)) {
            return List.of();
        }
        return reassignManager.getOcList().stream()
                .filter(row -> row.getFactionId() != null && row.getFactionId() == factionId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .map(TornSettingOcReassignOcDO::getOcName)
                .toList();
    }

    /**
     * 获取大锅饭普通收益排除规则。
     *
     * <p>派生规则：启用帮派的启用范围行按生效时间分组归并（NULL一组、每个日期一组），
     * 每组转一条{@link FactionOcExclusion}，与既有常量结构等价；仅读侧派生，不产生任何删除动作。</p>
     *
     * @return Key为帮派ID，值为该帮派的扁平排除规则列表
     */
    public Map<Long, List<FactionOcExclusion>> getExclusionRules() {
        Set<Long> enabledFactions = enabledFactionIds();
        Map<Long, List<TornSettingOcReassignOcDO>> rowsByFaction = reassignManager.getOcList().stream()
                .filter(row -> row.getFactionId() != null && enabledFactions.contains(row.getFactionId()))
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .collect(Collectors.groupingBy(TornSettingOcReassignOcDO::getFactionId,
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, List<FactionOcExclusion>> rules = new LinkedHashMap<>();
        for (Map.Entry<Long, List<TornSettingOcReassignOcDO>> entry : rowsByFaction.entrySet()) {
            rules.put(entry.getKey(), buildFactionRules(entry.getKey(), entry.getValue()));
        }
        return rules;
    }

    /**
     * 获取大锅饭帮派集合。
     *
     * @return 开启大锅饭且开关启用的帮派ID列表
     */
    public List<Long> getReassignFactionList() {
        return List.copyOf(enabledFactionIds());
    }

    /**
     * 解析帮派级大锅饭收益扫描起点。
     *
     * <p>派生规则：该帮派范围行中非NULL生效时间的最小值；全部为NULL时取{@code execTime}所在月份
     * 第一天。本方法为纯函数，仅依赖已缓存列表与入参。</p>
     *
     * @param factionId 帮派ID
     * @param execTime  执行时间
     * @return 大锅饭收益扫描起点（左闭区间）
     */
    public LocalDateTime resolveIncomeStartTime(long factionId, LocalDateTime execTime) {
        LocalDateTime earliest = reassignManager.getOcList().stream()
                .filter(row -> row.getFactionId() != null && row.getFactionId() == factionId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .map(TornSettingOcReassignOcDO::getEffectiveFrom)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (earliest != null) {
            return earliest;
        }
        return LocalDateTime.of(execTime.getYear(), execTime.getMonth(), 1, 0, 0, 0);
    }

    /**
     * 获取帮派大锅饭收益模式。
     *
     * <p>派生规则：帮派行的income_mode；无帮派行时默认COEFFICIENT（调用方应先以帮派集合门禁，
     * 默认值仅兜底）。</p>
     *
     * @param factionId 帮派ID
     * @return 收益模式，无帮派行时返回COEFFICIENT
     */
    public TornOcIncomeModeEnum getIncomeMode(long factionId) {
        return reassignManager.getFactionList().stream()
                .filter(row -> row.getFactionId() != null && row.getFactionId() == factionId)
                .map(TornSettingOcReassignFactionDO::getIncomeMode)
                .map(TornOcIncomeModeEnum::of)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(TornOcIncomeModeEnum.COEFFICIENT);
    }

    /**
     * 收集启用大锅饭的帮派ID集合（保持配置行顺序）。
     *
     * @return 启用帮派ID的有序集合
     */
    private Set<Long> enabledFactionIds() {
        return reassignManager.getFactionList().stream()
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .map(TornSettingOcReassignFactionDO::getFactionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * 将同一帮派的范围行按生效时间分组归并为排除规则：NULL一组（原有名单语义）、每个日期一组，
     * NULL组在前、日期组按时间升序，与既有常量结构等价。
     *
     * @param factionId 帮派ID
     * @param rows      该帮派的启用范围行
     * @return 扁平排除规则列表
     */
    private List<FactionOcExclusion> buildFactionRules(long factionId, List<TornSettingOcReassignOcDO> rows) {
        Map<LocalDateTime, List<String>> namesByEffectiveFrom = new LinkedHashMap<>();
        for (TornSettingOcReassignOcDO row : rows) {
            namesByEffectiveFrom.computeIfAbsent(row.getEffectiveFrom(), key -> new ArrayList<>())
                    .add(row.getOcName());
        }

        List<FactionOcExclusion> rules = new ArrayList<>();
        for (Map.Entry<LocalDateTime, List<String>> entry : namesByEffectiveFrom.entrySet()) {
            rules.add(new FactionOcExclusion(factionId, List.copyOf(entry.getValue()), entry.getKey()));
        }
        rules.sort(Comparator.comparing(FactionOcExclusion::getEffectiveFrom,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return List.copyOf(rules);
    }
}
