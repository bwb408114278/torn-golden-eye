package pn.torn.goldeneye.torn.service.faction.oc.reassign;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionOcPlanDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcReassignManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 大锅饭配置指令编排服务（开启/添加/名单的事务边界）。
 *
 * <p>添加成功时在同一事务内写入范围行并同步规划范围行，保持"大锅饭名单=规划范围"的镜像关系；
 * 缓存驱逐注册在事务提交成功后执行，事务同步不激活时直接驱逐。幂等由单实例部署契约下的指令
 * 校验保证，不引入数据库唯一约束。异步补算由策略层在事务外提交，防重入由批量收益服务保证。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcReassignConfigService {
    private final TornSettingOcReassignFactionDAO reassignFactionDao;
    private final TornSettingOcReassignOcDAO reassignOcDao;
    private final TornSettingFactionOcPlanDAO factionPlanDao;
    private final TornSettingFactionManager settingFactionManager;
    private final TornSettingOcReassignManager reassignManager;
    private final TornSettingOcPlanningManager planningManager;
    private final OcReassignAddValidator addValidator;

    /**
     * 开启帮派级大锅饭。
     *
     * <p>校验帮派存在于帮派设置表；已存在帮派行（含历史禁用行）时幂等拒绝；插入后注册事务提交
     * 成功驱逐门面缓存。</p>
     *
     * @param factionId  帮派ID
     * @param mode       收益模式
     * @param operatorId 操作人用户ID
     * @return 开启结果
     */
    @Transactional(rollbackFor = Exception.class)
    public OpenResult openFaction(long factionId, TornOcIncomeModeEnum mode, long operatorId) {
        if (!settingFactionManager.getIdMap().containsKey(factionId)) {
            return new OpenResult(false, "失败: 帮派不存在");
        }
        if (findFactionRow(factionId) != null) {
            return new OpenResult(false, "失败: 该帮派已开启大锅饭");
        }

        TornSettingOcReassignFactionDO row = new TornSettingOcReassignFactionDO();
        row.setFactionId(factionId);
        row.setIncomeMode(mode.getCode());
        row.setEnabled(true);
        reassignFactionDao.save(row);
        registerCacheEvictAfterCommit();
        log.info("大锅饭帮派开启: factionId={}, incomeMode={}, operatorId={}", factionId, mode.getCode(), operatorId);
        return new OpenResult(true, null);
    }

    /**
     * 添加帮派大锅饭OC范围行并同步规划范围。
     *
     * <p>生效日期为null时默认当月1日；前置校验全部通过后插入范围行（rank取目录值），
     * 规划行不存在时插入enabled=true行、已存在时跳过并在回执注明；事务提交成功后驱逐大锅饭
     * 门面与规划范围缓存。</p>
     *
     * @param factionId     目标帮派ID
     * @param ocName        OC名称
     * @param effectiveDate 生效日期，null时默认当月1日
     * @param operatorId    操作人用户ID
     * @return 添加结果，失败时携带可直接回执的原因文案
     */
    @Transactional(rollbackFor = Exception.class)
    public AddOcResult addOc(long factionId, String ocName, LocalDate effectiveDate, long operatorId) {
        LocalDate resolvedDate = effectiveDate == null ? LocalDate.now().withDayOfMonth(1) : effectiveDate;
        OcReassignAddValidator.AddValidationResult validation = addValidator.validate(factionId, ocName, resolvedDate);
        if (!validation.passed()) {
            return new AddOcResult(false, validation.failureMessage(), null, false, false);
        }

        LocalDateTime effectiveFrom = resolvedDate.atStartOfDay();
        TornSettingOcReassignOcDO row = new TornSettingOcReassignOcDO();
        row.setFactionId(factionId);
        row.setOcName(ocName);
        row.setRank(validation.rank());
        row.setEffectiveFrom(effectiveFrom);
        row.setEnabled(true);
        reassignOcDao.save(row);

        boolean planSynced = syncFactionPlanRow(factionId, ocName, validation.rank());
        registerCacheEvictAfterCommit();
        log.info("大锅饭OC添加: factionId={}, ocName={}, rank={}, effectiveFrom={}, planSynced={}, operatorId={}",
                factionId, ocName, validation.rank(), effectiveFrom, planSynced, operatorId);
        return new AddOcResult(true, null, effectiveFrom, planSynced, validation.profileMissing());
    }

    /**
     * 查询帮派大锅饭配置供名单指令渲染。
     *
     * @param factionId 帮派ID
     * @return 名单结果；帮派未开启大锅饭时返回失败结果
     */
    public ListResult listFaction(long factionId) {
        TornSettingOcReassignFactionDO factionRow = findFactionRow(factionId);
        if (factionRow == null || !Boolean.TRUE.equals(factionRow.getEnabled())) {
            return new ListResult(false, null, factionId, null, List.of());
        }

        Set<String> planOcNames = factionPlanDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanDO::getDeleted, 0)
                .eq(TornSettingFactionOcPlanDO::getFactionId, factionId)
                .list().stream()
                .map(TornSettingFactionOcPlanDO::getOcName)
                .collect(Collectors.toSet());

        List<ScopeRow> rows = reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getDeleted, 0)
                .eq(TornSettingOcReassignOcDO::getFactionId, factionId)
                .eq(TornSettingOcReassignOcDO::getEnabled, true)
                .orderByAsc(TornSettingOcReassignOcDO::getRank)
                .orderByAsc(TornSettingOcReassignOcDO::getOcName)
                .list().stream()
                .map(row -> new ScopeRow(row.getOcName(), row.getRank(), row.getEffectiveFrom(),
                        planOcNames.contains(row.getOcName())))
                .toList();
        return new ListResult(true, null, factionId,
                TornOcIncomeModeEnum.of(factionRow.getIncomeMode()), rows);
    }

    /**
     * 同步写入新队规划范围行：不存在该(faction_id, oc_name)时插入enabled=true行。
     *
     * @param factionId 帮派ID
     * @param ocName    OC名称
     * @param rank      OC级别
     * @return true表示本次插入，false表示规划行已存在
     */
    private boolean syncFactionPlanRow(long factionId, String ocName, Integer rank) {
        boolean exists = factionPlanDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanDO::getDeleted, 0)
                .eq(TornSettingFactionOcPlanDO::getFactionId, factionId)
                .eq(TornSettingFactionOcPlanDO::getOcName, ocName)
                .exists();
        if (exists) {
            return false;
        }

        TornSettingFactionOcPlanDO planRow = new TornSettingFactionOcPlanDO();
        planRow.setFactionId(factionId);
        planRow.setOcName(ocName);
        planRow.setRank(rank);
        planRow.setEnabled(true);
        factionPlanDao.save(planRow);
        return true;
    }

    /**
     * 查询帮派的大锅饭开关行（含禁用行，用于幂等判断）。
     *
     * @param factionId 帮派ID
     * @return 开关行，不存在时返回null
     */
    private TornSettingOcReassignFactionDO findFactionRow(long factionId) {
        return reassignFactionDao.lambdaQuery()
                .eq(TornSettingOcReassignFactionDO::getDeleted, 0)
                .eq(TornSettingOcReassignFactionDO::getFactionId, factionId)
                .one();
    }

    /**
     * 在事务提交成功后驱逐大锅饭门面与规划范围缓存；事务同步不激活（如无事务上下文）时直接驱逐。
     */
    private void registerCacheEvictAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reassignManager.refreshCache();
            planningManager.refreshCache();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reassignManager.refreshCache();
                planningManager.refreshCache();
            }
        });
    }

    /**
     * 帮派开启结果。
     *
     * @param success       是否成功
     * @param failureReason 失败原因文案，成功时为null
     */
    public record OpenResult(
            boolean success,
            String failureReason) {
    }

    /**
     * OC添加结果。
     *
     * @param success        是否成功
     * @param failureReason  失败原因文案，成功时为null
     * @param effectiveFrom  生效时间，失败时为null
     * @param planSynced     规划范围行是否由本次插入（false表示规划行已存在）
     * @param profileMissing 是否缺少新队规划档案（警告不阻断）
     */
    public record AddOcResult(
            boolean success,
            String failureReason,
            LocalDateTime effectiveFrom,
            boolean planSynced,
            boolean profileMissing) {
    }

    /**
     * 名单查询结果。
     *
     * @param success       是否成功
     * @param failureReason 失败原因文案，成功时为null
     * @param factionId     帮派ID
     * @param incomeMode    收益模式，失败时为null
     * @param rows          范围行列表，按级别与名称排序
     */
    public record ListResult(
            boolean success,
            String failureReason,
            long factionId,
            TornOcIncomeModeEnum incomeMode,
            List<ScopeRow> rows) {
    }

    /**
     * 名单范围行。
     *
     * @param ocName        OC名称
     * @param rank          OC级别
     * @param effectiveFrom 生效时间，null表示始终
     * @param planRowExists 规划范围行是否存在
     */
    public record ScopeRow(
            String ocName,
            Integer rank,
            LocalDateTime effectiveFrom,
            boolean planRowExists) {
    }
}
