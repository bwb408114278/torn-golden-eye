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
 * @version 1.2.7
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
     * @param topN 返回Top N个推荐
     */
    public List<OcRecommendationVO> recommendOcForUser(TornUserDO user, int topN, OcSlotDictBO joinedOc) {
        // 1. 查询所有招募中的OC
        List<TornFactionOcDO> recruitOcList = findRecrutList(user.getFactionId(), joinedOc);
        if (CollectionUtils.isEmpty(recruitOcList)) {
            return List.of();
        }

        // 2. 查询所有未满员的OC
        List<TornFactionOcSlotDO> emptySlotList = findEmptySlotList(recruitOcList, joinedOc);
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
        sorted = filterBelowBaseline(sorted, joinedOc);
        return sorted.stream().limit(topN).toList();
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
     * 已加入队伍时，过滤掉评分低于当前队的推荐
     */
    private List<OcRecommendationVO> filterBelowBaseline(List<OcRecommendationVO> sorted, OcSlotDictBO joinedOc) {
        if (joinedOc == null) {
            return sorted;
        }
        BigDecimal currentScore = sorted.stream()
                .filter(r -> r.getOcId().equals(joinedOc.getOc().getId())
                        && r.getRecommendedPosition().equals(joinedOc.getSlot().getPosition()))
                .map(OcRecommendationVO::getRecommendScore)
                .findFirst().orElse(null);
        if (currentScore == null) {
            return sorted;
        }
        return sorted.stream()
                .filter(r -> r.getRecommendScore().compareTo(currentScore) >= 0)
                .toList();
    }

    /**
     * 查找招募中的OC列表
     */
    public List<TornFactionOcDO> findRecrutList(long factionId, OcSlotDictBO joinedOcSlot) {
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
     * 查找招募中的OC空位
     */
    public List<TornFactionOcSlotDO> findEmptySlotList(List<TornFactionOcDO> recruitOcList,
                                                       OcSlotDictBO joinedOcSlot) {
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(recruitOcList);
        if (joinedOcSlot != null) {
            emptySlotList.add(joinedOcSlot.getSlot());
        }

        return emptySlotList;
    }
}