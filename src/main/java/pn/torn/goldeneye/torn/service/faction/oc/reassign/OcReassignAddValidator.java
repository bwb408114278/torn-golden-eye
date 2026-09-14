package pn.torn.goldeneye.torn.service.faction.oc.reassign;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.setting.*;
import pn.torn.goldeneye.torn.manager.setting.*;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 大锅饭OC添加前置校验组件。
 *
 * <p>只读无副作用，按固定顺序完成帮派开通、目录存在、岗位齐全、链完整性、系数完整、生效时间、
 * 幂等与规划档案八项校验，任一失败立即返回带原因的结果，不以异常控制流程。系数完整性校验直接
 * 复用{@link TornSettingOcCoefficientManager}的运行时解析口径，防止两处口径漂移。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Component
@RequiredArgsConstructor
public class OcReassignAddValidator {
    private final TornSettingOcReassignFactionDAO reassignFactionDao;
    private final TornSettingOcReassignOcDAO reassignOcDao;
    private final TornSettingOcManager settingOcManager;
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornSettingOcPlanningManager planningManager;
    private final TornSettingOcCoefficientManager coefficientManager;
    private final TornSettingOcReassignManager reassignManager;

    /**
     * 执行添加前置校验（按7.3固定顺序，任一失败立即返回）。
     *
     * @param factionId     目标帮派ID
     * @param ocName        OC名称
     * @param effectiveDate 生效日期（已解析，允许为null由调用方默认当月1日后再传入）
     * @return 校验结果，失败时携带可直接回执的原因文案
     */
    public AddValidationResult validate(long factionId, String ocName, LocalDate effectiveDate) {
        if (!isFactionEnabled(factionId)) {
            return AddValidationResult.fail("失败: 帮派未开启大锅饭, 请联系超管执行[OC大锅饭开启]");
        }

        TornSettingOcDO catalog = findCatalog(ocName);
        if (catalog == null || catalog.getRank() == null) {
            return AddValidationResult.fail("失败: 目录中不存在该OC, 请先执行[OC校准]触发目录自动同步");
        }

        List<String> slotCodes = findSlotCodes(ocName, catalog.getRank());
        if (slotCodes.size() != catalog.getRequiredMembers()) {
            return AddValidationResult.fail("失败: 岗位目录不完整(" + slotCodes.size() + "/"
                    + catalog.getRequiredMembers() + "), 请先执行[OC校准]补齐");
        }

        String missingPredecessor = findMissingChainPredecessor(factionId, ocName);
        if (missingPredecessor != null) {
            return AddValidationResult.fail("失败: 链式OC缺少前序节点: 添加[" + ocName + "]需先加入["
                    + missingPredecessor + "]");
        }

        if (reassignManager.getIncomeMode(factionId) == TornOcIncomeModeEnum.COEFFICIENT) {
            List<String> missingSlots = coefficientManager
                    .hasCompleteCoefficients(factionId, ocName, catalog.getRank(), slotCodes);
            if (!missingSlots.isEmpty()) {
                return AddValidationResult.fail("失败: 系数不完整, 缺少岗位系数: "
                        + String.join(", ", missingSlots) + ", 请先维护系数表");
            }
        }

        if (effectiveDate != null && effectiveDate.isAfter(LocalDate.now())) {
            return AddValidationResult.fail("失败: 生效日期不能晚于今天");
        }

        TornSettingOcReassignOcDO existing = findEnabledScopeRow(factionId, ocName);
        if (existing != null) {
            return AddValidationResult.fail("失败: 该OC已在大锅饭名单中, 生效时间: "
                    + formatEffectiveFrom(existing.getEffectiveFrom()));
        }

        boolean profileMissing = planningManager.getProfiles().stream()
                .noneMatch(profile -> profile.getOcName().equals(ocName));
        return new AddValidationResult(true, null, catalog.getRank(), profileMissing);
    }

    /**
     * 校验帮派已开启大锅饭且开关启用。
     *
     * @param factionId 帮派ID
     * @return 已启用返回true
     */
    private boolean isFactionEnabled(long factionId) {
        return reassignFactionDao.lambdaQuery()
                .eq(TornSettingOcReassignFactionDO::getDeleted, 0)
                .eq(TornSettingOcReassignFactionDO::getFactionId, factionId)
                .eq(TornSettingOcReassignFactionDO::getEnabled, true)
                .exists();
    }

    /**
     * 按名称查找OC目录行。
     *
     * @param ocName OC名称
     * @return 目录行，不存在时返回null
     */
    private TornSettingOcDO findCatalog(String ocName) {
        return settingOcManager.getList().stream()
                .filter(oc -> oc.getOcName().equals(ocName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 收集OC对应级别的岗位编码列表（去重保序）。
     *
     * @param ocName OC名称
     * @param rank   OC级别
     * @return 岗位编码列表
     */
    private List<String> findSlotCodes(String ocName, Integer rank) {
        return settingOcSlotManager.getList().stream()
                .filter(slot -> slot.getOcName().equals(ocName))
                .filter(slot -> Objects.equals(slot.getRank(), rank))
                .map(TornSettingOcSlotDO::getSlotCode)
                .distinct()
                .toList();
    }

    /**
     * 查找链完整性校验缺失的前序节点。
     *
     * <p>目标是启用链的子节点时，该链从头到目标边的全部父节点（按sequenceNo顺序，含直接前序）
     * 必须已在帮派大锅饭名单中；链根单独添加合法（进入等待后继状态）。返回链序上第一个缺失的
     * 前序节点名称，无缺失或目标不是任何链的子节点时返回null。</p>
     *
     * @param factionId 帮派ID
     * @param ocName    目标OC名称
     * @return 缺失的前序节点名称，无需校验时返回null
     */
    private String findMissingChainPredecessor(long factionId, String ocName) {
        List<TornSettingOcChainDO> chains = planningManager.getChains();
        List<String> rotationNames = reassignManager.getRotationOcNames(factionId);
        for (TornSettingOcChainDO edge : chains) {
            if (!edge.getChildOcName().equals(ocName)) {
                continue;
            }
            for (TornSettingOcChainDO predecessor : chains) {
                boolean sameChainUpToTarget = predecessor.getChainCode().equals(edge.getChainCode())
                        && predecessor.getSequenceNo() <= edge.getSequenceNo();
                if (sameChainUpToTarget && !rotationNames.contains(predecessor.getParentOcName())) {
                    return predecessor.getParentOcName();
                }
            }
        }
        return null;
    }

    /**
     * 查询同帮派同OC的启用范围行（幂等校验）。
     *
     * @param factionId 帮派ID
     * @param ocName    OC名称
     * @return 已存在的启用范围行，不存在时返回null
     */
    private TornSettingOcReassignOcDO findEnabledScopeRow(long factionId, String ocName) {
        return reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getDeleted, 0)
                .eq(TornSettingOcReassignOcDO::getFactionId, factionId)
                .eq(TornSettingOcReassignOcDO::getOcName, ocName)
                .eq(TornSettingOcReassignOcDO::getEnabled, true)
                .one();
    }

    /**
     * 格式化生效时间，null表示始终生效。
     *
     * @param effectiveFrom 生效时间
     * @return 回执文本
     */
    private String formatEffectiveFrom(LocalDateTime effectiveFrom) {
        return effectiveFrom == null ? "始终" : DateTimeUtils.convertToString(effectiveFrom);
    }

    /**
     * 添加前置校验结果。
     *
     * @param passed         是否全部通过
     * @param failureMessage 失败原因文案，通过时为null
     * @param rank           目录中的OC级别，通过时用于落库
     * @param profileMissing 是否缺少新队规划档案（警告不阻断）
     */
    public record AddValidationResult(
            boolean passed,
            String failureMessage,
            Integer rank,
            boolean profileMissing) {

        /**
         * 构造失败结果。
         *
         * @param failureMessage 失败原因文案
         * @return 失败结果
         */
        static AddValidationResult fail(String failureMessage) {
            return new AddValidationResult(false, failureMessage, null, false);
        }
    }
}
