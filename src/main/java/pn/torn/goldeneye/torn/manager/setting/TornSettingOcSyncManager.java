package pn.torn.goldeneye.torn.manager.setting;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcSlotDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OC设置目录自动同步公共逻辑层。
 *
 * <p>同步OC数据时基于available数据自动补齐完全缺失的全局OC目录及其岗位目录；
 * 已存在同rank的OC跳过，rank漂移仅记录告警等待人工校准，绝不更新既有人工配置。
 * 当前单实例部署契约下仅用JVM共享锁收敛并发，不引入分布式锁或数据库唯一约束。</p>
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.08.29
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingOcSyncManager {
    /**
     * JVM内目录同步共享锁：保护"读取目录→判定缺失→插入目录与岗位→事务完成"临界区
     */
    private static final Object SYNC_LOCK = new Object();
    /**
     * 新岗位目录默认成功率
     */
    private static final int DEFAULT_SLOT_PASS_RATE = 60;
    /**
     * 新岗位目录默认权重占比
     */
    private static final int DEFAULT_SLOT_PRIORITY = 0;
    /**
     * 新OC目录默认预期收益
     */
    private static final long DEFAULT_EXPECTED_REWARD = 0L;
    /**
     * 新岗位目录默认大成功占比
     */
    private static final BigDecimal DEFAULT_BEST_SUCCESS = BigDecimal.ZERO;

    private final TornSettingOcDAO settingOcDao;
    private final TornSettingOcSlotDAO settingOcSlotDao;
    private final TornSettingOcManager settingOcManager;
    private final TornSettingOcSlotManager settingOcSlotManager;
    @Lazy
    @Resource
    private TornSettingOcSyncManager syncManager;

    /**
     * 目录同步入口。
     *
     * <p>JVM锁持有至内部独立事务afterCompletion后才释放（事务在代理调用内完成提交），
     * 保证下一线程进入临界区前必然看到前一线程已提交的目录。</p>
     *
     * @param availableList 同一次刷新返回的available OC列表
     */
    public void syncMissingAvailable(List<TornFactionCrimeVO> availableList) {
        if (CollectionUtils.isEmpty(availableList)) {
            return;
        }

        synchronized (SYNC_LOCK) {
            syncManager.syncMissingInNewTransaction(availableList);
        }
    }

    /**
     * 独立事务内补齐缺失目录。
     *
     * <p>锁内直查DAO读取当前有效目录（不读取可能含旧值的Caffeine缓存）；
     * 纯内存构造待新增列表后先插OC再插岗位，任一SQL异常整体回滚。</p>
     *
     * @param availableList 同一次刷新返回的available OC列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncMissingInNewTransaction(List<TornFactionCrimeVO> availableList) {
        List<TornSettingOcDO> catalog = settingOcDao.list();
        MissingCatalogPlan plan = buildMissingPlan(availableList, catalog);
        if (plan.ocList().isEmpty()) {
            return;
        }

        int ocRows = settingOcDao.insertMissingBatch(plan.ocList());
        int slotRows = settingOcSlotDao.insertMissingBatch(plan.slotList());
        registerCacheEvictAfterCommit(ocRows + slotRows);
        log.info("OC目录自动同步完成, 新增OC目录数={}, 新增岗位目录数={}, 来源available OC数={}",
                ocRows, slotRows, availableList.size());
    }

    /**
     * 构造缺失目录补齐计划：按名称分组后逐名称判定缺失、已存在或rank漂移。
     *
     * @param availableList available OC列表
     * @param catalog       锁内直查的当前有效OC目录
     * @return 待新增OC与岗位列表组成的计划
     */
    private MissingCatalogPlan buildMissingPlan(List<TornFactionCrimeVO> availableList, List<TornSettingOcDO> catalog) {
        Map<String, List<TornFactionCrimeVO>> availableByName = groupValidByName(availableList);
        List<TornSettingOcDO> ocList = new ArrayList<>();
        List<TornSettingOcSlotDO> slotList = new ArrayList<>();
        for (Map.Entry<String, List<TornFactionCrimeVO>> entry : availableByName.entrySet()) {
            appendCatalogPlan(entry.getKey(), entry.getValue(), catalog, ocList, slotList);
        }
        return new MissingCatalogPlan(ocList, slotList);
    }

    /**
     * 按单个OC名称判定并追加目录计划：已存在时仅对rank漂移告警，缺失时以首个完整模板构造。
     *
     * @param ocName    OC名称
     * @param instances 同次快照内该名称的全部实例
     * @param catalog   当前有效OC目录
     * @param ocList    待新增OC列表（输出）
     * @param slotList  待新增岗位列表（输出）
     */
    private void appendCatalogPlan(String ocName, List<TornFactionCrimeVO> instances, List<TornSettingOcDO> catalog,
                                   List<TornSettingOcDO> ocList, List<TornSettingOcSlotDO> slotList) {
        List<Integer> catalogRanks = catalog.stream()
                .filter(oc -> oc.getOcName().equals(ocName))
                .map(TornSettingOcDO::getRank)
                .toList();
        if (!catalogRanks.isEmpty()) {
            warnRankDrift(ocName, catalogRanks, instances);
            return;
        }

        List<Integer> availableRanks = instances.stream().map(TornFactionCrimeVO::getRank).distinct().toList();
        if (availableRanks.size() > 1) {
            log.warn("OC目录自动同步跳过: ocName={}, availableRank不唯一={}, 处理结果=跳过并等待人工校准, 原因=无法确认首次目录rank",
                    ocName, availableRanks);
            return;
        }

        TornFactionCrimeVO template = findCompleteTemplate(instances);
        if (template == null) {
            log.warn("OC目录自动同步跳过: ocName={}, rank={}, 处理结果=跳过, 原因=岗位数据为空或岗位编码不完整",
                    ocName, availableRanks.getFirst());
            return;
        }

        List<TornSettingOcSlotDO> slots = buildSlots(ocName, template.getRank(), template.getSlots());
        slotList.addAll(slots);
        ocList.add(buildOc(ocName, template.getRank(), slots.size()));
    }

    /**
     * 对已存在目录名称的rank漂移告警：按API rank去重，每个漂移rank一条warn，不写库。
     *
     * @param ocName       OC名称
     * @param catalogRanks 目录中该名称的全部rank
     * @param instances    同次快照内该名称的全部实例
     */
    private void warnRankDrift(String ocName, List<Integer> catalogRanks, List<TornFactionCrimeVO> instances) {
        List<Integer> distinctCatalogRanks = catalogRanks.stream().distinct().toList();
        instances.stream()
                .map(TornFactionCrimeVO::getRank)
                .distinct()
                .filter(rank -> !distinctCatalogRanks.contains(rank))
                .forEach(rank -> log.warn(
                        "OC目录rank漂移告警: ocName={}, catalogRank={}, availableRank={}, 处理结果=跳过并等待人工校准",
                        ocName, distinctCatalogRanks, rank));
    }

    /**
     * 按名称分组有效实例，name或rank缺失的实例告警后跳过。
     *
     * @param availableList available OC列表
     * @return 名称到实例列表的有序映射
     */
    private Map<String, List<TornFactionCrimeVO>> groupValidByName(List<TornFactionCrimeVO> availableList) {
        Map<String, List<TornFactionCrimeVO>> availableByName = new LinkedHashMap<>();
        for (TornFactionCrimeVO oc : availableList) {
            if (!StringUtils.hasText(oc.getName()) || oc.getRank() == null) {
                log.warn("OC目录自动同步跳过: name={}, rank={}, 处理结果=跳过, 原因=必要目录字段缺失",
                        oc.getName(), oc.getRank());
                continue;
            }

            availableByName.computeIfAbsent(oc.getName(), key -> new ArrayList<>()).add(oc);
        }
        return availableByName;
    }

    /**
     * 选取首个岗位完整且可生成有效岗位编码的同键实例作为模板，其他实例不参与岗位合并。
     *
     * @param instances 同名称实例列表
     * @return 模板实例，无完整实例时返回null
     */
    private TornFactionCrimeVO findCompleteTemplate(List<TornFactionCrimeVO> instances) {
        for (TornFactionCrimeVO instance : instances) {
            if (hasCompleteSlots(instance.getSlots())) {
                return instance;
            }
        }
        return null;
    }

    /**
     * 判断实例岗位是否完整：非空且每个岗位均可生成有效编码。
     *
     * @param slots 岗位列表
     * @return 完整返回true
     */
    private boolean hasCompleteSlots(List<TornFactionCrimeSlotVO> slots) {
        if (CollectionUtils.isEmpty(slots)) {
            return false;
        }

        return slots.stream().allMatch(slot -> StringUtils.hasText(slot.getPosition())
                && slot.getPositionInfo() != null
                && slot.getPositionInfo().getNumber() != null);
    }

    /**
     * 构造去重后的岗位目录：slot_code重复时保留首次出现并告警。
     *
     * @param ocName OC名称
     * @param rank   OC级别
     * @param slots  模板实例岗位列表
     * @return 去重后的岗位目录列表
     */
    private List<TornSettingOcSlotDO> buildSlots(String ocName, Integer rank, List<TornFactionCrimeSlotVO> slots) {
        Map<String, TornSettingOcSlotDO> slotMap = new LinkedHashMap<>();
        for (TornFactionCrimeSlotVO slot : slots) {
            String slotCode = slot.getPosition() + "#" + slot.getPositionInfo().getNumber();
            TornSettingOcSlotDO existSlot = slotMap.putIfAbsent(slotCode, buildSlot(ocName, rank, slotCode, slot));
            if (existSlot != null) {
                log.warn("OC目录自动同步岗位编码重复, 保留首次出现: ocName={}, rank={}, slotCode={}",
                        ocName, rank, slotCode);
            }
        }
        return List.copyOf(slotMap.values());
    }

    /**
     * 构造单个岗位目录默认值。
     *
     * @param ocName   OC名称
     * @param rank     OC级别
     * @param slotCode 岗位编码
     * @param slot     岗位实例
     * @return 岗位目录
     */
    private TornSettingOcSlotDO buildSlot(String ocName, Integer rank, String slotCode, TornFactionCrimeSlotVO slot) {
        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setOcName(ocName);
        slotSetting.setRank(rank);
        slotSetting.setSlotCode(slotCode);
        slotSetting.setSlotShortCode(slot.getPosition());
        slotSetting.setPassRate(DEFAULT_SLOT_PASS_RATE);
        slotSetting.setPriority(DEFAULT_SLOT_PRIORITY);
        slotSetting.setBestSuccess(DEFAULT_BEST_SUCCESS);
        return slotSetting;
    }

    /**
     * 构造OC目录默认值：人数与准备天数等于去重岗位数，预期收益为0。
     *
     * @param ocName    OC名称
     * @param rank      OC级别
     * @param slotCount 去重后岗位数
     * @return OC目录
     */
    private TornSettingOcDO buildOc(String ocName, Integer rank, int slotCount) {
        TornSettingOcDO ocSetting = new TornSettingOcDO();
        ocSetting.setOcName(ocName);
        ocSetting.setRank(rank);
        ocSetting.setRequiredMembers(slotCount);
        ocSetting.setPrepareDays(slotCount);
        ocSetting.setExpectedReward(DEFAULT_EXPECTED_REWARD);
        return ocSetting;
    }

    /**
     * 实际插入行数大于0时在事务提交成功后驱逐OC与岗位两类Caffeine缓存；
     * 事务同步不激活（如无事务上下文）时直接驱逐。
     *
     * @param insertedRows 两个Mapper返回的实际插入总行数
     */
    private void registerCacheEvictAfterCommit(int insertedRows) {
        if (insertedRows <= 0) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            settingOcManager.refreshCache();
            settingOcSlotManager.refreshCache();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                settingOcManager.refreshCache();
                settingOcSlotManager.refreshCache();
            }
        });
    }

    /**
     * 目录同步构造结果。
     *
     * @param ocList   待新增OC目录列表
     * @param slotList 待新增岗位目录列表
     */
    private record MissingCatalogPlan(
            List<TornSettingOcDO> ocList,
            List<TornSettingOcSlotDO> slotList) {
    }
}
