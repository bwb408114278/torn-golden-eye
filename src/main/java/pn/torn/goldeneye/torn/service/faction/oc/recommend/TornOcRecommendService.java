package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotDictBO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OC队伍推荐逻辑层
 *
 * @author Bai
 * @version 1.3.6
 * @since 2025.11.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcRecommendService {
    private final TornOcRecommendManager ocRecommendManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 为用户推荐OC队伍和岗位，权重：停转时间 > 成功率
     *
     * @param user     用户
     * @param topN     返回Top N个推荐
     * @param joinedOc 当前占用的OC岗位
     * @return 可切换的推荐岗位列表
     */
    public List<OcRecommendationVO> recommendOcForUser(TornUserDO user, int topN, OcSlotDictBO joinedOc) {
        // 1. 查询所有招募中的OC
        List<TornFactionOcDO> recruitOcList = findRecrutList(user.getFactionId(), joinedOc);
        if (CollectionUtils.isEmpty(recruitOcList)) {
            return List.of();
        }

        // 2. 查询所有未满员的OC
        List<TornFactionOcSlotDO> emptySlotList = findEmptySlotList(recruitOcList);
        if (CollectionUtils.isEmpty(emptySlotList)) {
            return List.of();
        }

        // 3. 查询用户成功率数据
        List<TornFactionOcUserDO> userOcData = ocUserDao.queryByUserId(user.getId());
        if (CollectionUtils.isEmpty(userOcData)) {
            return List.of();
        }

        // 4. 为每个OC的每个空闲岗位计算推荐度
        boolean isReassign = ocRecommendManager.checkIsReassignRecommended(user, userOcData);
        CurrentOcStatus currentStatus = buildCurrentOcStatus(user, joinedOc, userOcData, isReassign);
        List<OcRecommendationVO> recommendations = new ArrayList<>();
        for (TornFactionOcDO oc : recruitOcList) {
            if (shouldSkipOc(isReassign, user.getFactionId(), oc, joinedOc)) {
                continue;
            }
            collectSlotsScore(oc, emptySlotList, user.getFactionId(), userOcData, isReassign, recommendations);
        }

        // 5. 按推荐度排序
        List<OcRecommendationVO> sorted = recommendations.stream()
                .sorted(Comparator.comparing(OcRecommendationVO::getRecommendScore).reversed())
                .toList();

        // 6. 以当前队评分为基线过滤，返回Top N
        // 当前OC已被禁用时，当前OC不是合法加入目标，其评分不能作为正常候选的过滤基线。
        BigDecimal baseline = currentStatus.disabled() ? null : currentStatus.currentScore();
        sorted = filterBelowBaseline(sorted, baseline);
        return sorted.stream().limit(topN).toList();
    }

    /**
     * 查询用户当前OC状态，供指令层输出明确的当前状态提示。
     *
     * @param user     用户
     * @param joinedOc 当前占用的OC岗位
     * @return 当前状态；未入队时返回joined为false的状态
     */
    public CurrentOcStatus queryCurrentOcStatus(TornUserDO user, OcSlotDictBO joinedOc) {
        if (joinedOc == null) {
            return CurrentOcStatus.notJoined();
        }

        List<TornFactionOcUserDO> userOcData = ocUserDao.queryByUserId(user.getId());
        boolean isReassign = ocRecommendManager.checkIsReassignRecommended(user, userOcData);
        return buildCurrentOcStatus(user, joinedOc, userOcData, isReassign);
    }

    private CurrentOcStatus buildCurrentOcStatus(TornUserDO user, OcSlotDictBO joinedOc,
                                                 List<TornFactionOcUserDO> userOcData,
                                                 boolean isReassign) {
        if (joinedOc == null) {
            return CurrentOcStatus.notJoined();
        }

        TornFactionOcDO oc = joinedOc.getOc();
        TornFactionOcSlotDO slot = joinedOc.getSlot();
        boolean disabled = ocRecommendManager.isOcDisabled(user.getFactionId(), oc);
        TornSettingOcSlotDO requirement = ocRecommendManager.findSlotRequirement(user.getFactionId(), oc, slot);
        TornFactionOcUserDO passRateData = ocRecommendManager.findUserPassRate(userOcData, oc, requirement);
        Integer actualPassRate = passRateData == null ? null : passRateData.getPassRate();
        Integer requiredPassRate = requirement == null ? null : requirement.getPassRate();
        boolean passRateInsufficient = actualPassRate != null && requiredPassRate != null
                && actualPassRate < requiredPassRate;
        BigDecimal currentScore = calculateCurrentScore(isReassign, oc, requirement, passRateData);
        return new CurrentOcStatus(true, disabled, passRateInsufficient,
                actualPassRate, requiredPassRate, currentScore);
    }

    private BigDecimal calculateCurrentScore(boolean isReassign, TornFactionOcDO oc,
                                             TornSettingOcSlotDO requirement,
                                             TornFactionOcUserDO passRateData) {
        if (requirement == null || passRateData == null
                || passRateData.getPassRate() < requirement.getPassRate()) {
            return null;
        }
        return ocRecommendManager.calcRecommendScore(isReassign, oc, requirement, passRateData);
    }

    /**
     * 大锅饭模式下，检查是否应跳过该OC（当前队伍永远不跳过）
     */
    private boolean shouldSkipOc(boolean isReassign, long factionId, TornFactionOcDO oc, OcSlotDictBO joinedOc) {
        if (!isReassign) {
            return false;
        }
        if (TornConstants.ROTATION_OC_NAME.get(factionId).contains(oc.getName())) {
            return false;
        }
        return joinedOc == null || !joinedOc.getOc().getId().equals(oc.getId());
    }

    /**
     * 评估单个OC的所有空闲槽位，符合条件的加入推荐列表
     */
    private void collectSlotsScore(TornFactionOcDO oc, List<TornFactionOcSlotDO> emptySlotList,
                                   long factionId, List<TornFactionOcUserDO> userOcData,
                                   boolean isReassign, List<OcRecommendationVO> recommendations) {
        List<TornFactionOcSlotDO> vacantSlots = emptySlotList.stream()
                .filter(s -> s.getOcId().equals(oc.getId())).toList();
        for (TornFactionOcSlotDO slot : vacantSlots) {
            TornSettingOcSlotDO slotSetting = ocRecommendManager.findSlotSetting(factionId, oc, slot);
            TornFactionOcUserDO matchedData = ocRecommendManager.findUserPassRate(userOcData, oc, slotSetting);
            if (slotSetting == null || matchedData == null
                    || matchedData.getPassRate() < slotSetting.getPassRate()) {
                continue;
            }
            BigDecimal recommendScore = ocRecommendManager.calcRecommendScore(isReassign, oc, slotSetting, matchedData);
            String recommentReason = ocRecommendManager.buildRecommendReason(oc.getReadyTime(), matchedData.getPassRate());
            recommendations.add(new OcRecommendationVO(oc, slot, recommendScore, recommentReason));
        }
    }

    /**
     * 已加入队伍时，过滤掉评分低于当前队的推荐。
     *
     * @param sorted       已按评分排序的推荐列表
     * @param currentScore 当前岗位评分基线
     * @return 不低于当前岗位评分的推荐列表
     */
    private List<OcRecommendationVO> filterBelowBaseline(List<OcRecommendationVO> sorted,
                                                         BigDecimal currentScore) {
        if (currentScore == null) {
            return sorted;
        }
        return sorted.stream()
                .filter(r -> r.getRecommendScore().compareTo(currentScore) >= 0)
                .toList();
    }

    /**
     * 查找推荐候选OC列表。
     *
     * <p>当前已加入禁用OC且有进度时，恢复为帮派正常招募OC范围；当前OC未禁用且有进度时仍只返回当前OC，保持原有行为。</p>
     *
     * @param factionId    帮派ID
     * @param joinedOcSlot 当前占用的OC岗位，未加入时传null
     * @return 推荐候选OC列表
     */
    public List<TornFactionOcDO> findRecrutList(long factionId, OcSlotDictBO joinedOcSlot) {
        // 当前已加入禁用OC且有进度时，恢复为帮派正常招募OC范围，禁用OC本身不作为候选。
        if (shouldRecommendNormalOcForDisabledCurrentOc(factionId, joinedOcSlot)) {
            return ocDao.queryRecrutList(factionId);
        }
        // 跑了进度的, 只能判断当前队, 可以换位置
        if (joinedOcSlot != null && BigDecimal.ZERO.compareTo(joinedOcSlot.getSlot().getProgress()) < 0) {
            return List.of(joinedOcSlot.getOc());
        }

        List<TornFactionOcDO> recruitOcList = ocDao.queryRecrutList(factionId);
        if (joinedOcSlot == null) {
            return recruitOcList;
        }

        // 针对于空转进度的，按照进度跑完时间减一天去计算 如果OC已经进入Planning状态, 手动放到recruit列表中
        TornFactionOcDO joinedOc = joinedOcSlot.getOc();
        TornFactionOcDO calcOc = recruitOcList.stream()
                .filter(o -> o.getId().equals(joinedOc.getId()))
                .findAny().orElse(null);
        if (calcOc != null) {
            calcOc.setReadyTime(calcOc.getReadyTime().minusDays(1));
        } else {
            joinedOc.setReadyTime(joinedOc.getReadyTime().minusDays(1));
            recruitOcList.add(joinedOc);
        }
        return recruitOcList;
    }

    /**
     * 判断是否应为禁用当前OC且有进度的用户恢复正常招募OC候选范围。
     *
     * @param factionId    帮派ID
     * @param joinedOcSlot 当前占用的OC岗位
     * @return true表示当前OC被禁用且有进度，应使用正常招募OC作为推荐范围
     */
    private boolean shouldRecommendNormalOcForDisabledCurrentOc(long factionId, OcSlotDictBO joinedOcSlot) {
        return joinedOcSlot != null
                && BigDecimal.ZERO.compareTo(joinedOcSlot.getSlot().getProgress()) < 0
                && ocRecommendManager.isOcDisabled(factionId, joinedOcSlot.getOc());
    }

    /**
     * 查找招募中的OC空位，当前占用岗位不作为候选。
     *
     * @param recruitOcList 招募中的OC列表
     * @return 空闲岗位列表
     */
    public List<TornFactionOcSlotDO> findEmptySlotList(List<TornFactionOcDO> recruitOcList) {
        return ocSlotDao.queryEmptySlotList(recruitOcList);
    }

    /**
     * 当前OC状态及评分基线。
     *
     * @param joined               是否已加入OC
     * @param disabled             当前OC是否被禁用
     * @param passRateInsufficient 当前岗位成功率是否低于要求
     * @param actualPassRate       当前实际成功率
     * @param requiredPassRate     岗位要求成功率
     * @param currentScore         当前岗位评分基线
     */
    public record CurrentOcStatus(boolean joined, boolean disabled, boolean passRateInsufficient,
                                  Integer actualPassRate, Integer requiredPassRate,
                                  BigDecimal currentScore) {
        private static CurrentOcStatus notJoined() {
            return new CurrentOcStatus(false, false, false, null, null, null);
        }
    }
}
