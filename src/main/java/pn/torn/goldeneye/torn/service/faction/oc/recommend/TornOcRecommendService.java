package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
 * @version 0.3.0
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
        List<OcRecommendationVO> recommendations = new ArrayList<>();
        for (TornFactionOcDO oc : recruitOcList) {
            // 查询当前OC下所有空闲岗位
            List<TornFactionOcSlotDO> vacantSlots = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList();
            // 尝试匹配每个空闲岗位
            for (TornFactionOcSlotDO slot : vacantSlots) {
                TornSettingOcSlotDO slotSetting = ocRecommendManager.findSlotSetting(oc, slot);
                TornFactionOcUserDO matchedData = ocRecommendManager.findUserPassRate(userOcData, oc, slotSetting);
                boolean isNotMatch = matchedData == null || matchedData.getPassRate() < slotSetting.getPassRate();
                if (slotSetting == null || isNotMatch) {
                    continue;
                }

                // 计算推荐度评分
                BigDecimal recommendScore = ocRecommendManager.calcRecommendScore(
                        user, userOcData, oc, slotSetting, matchedData);
                String recommentReason = ocRecommendManager.buildRecommendReason(oc, matchedData.getPassRate());
                recommendations.add(new OcRecommendationVO(oc, slot, recommendScore, recommentReason));
            }
        }

        // 5. 按推荐度排序，返回Top N
        return recommendations.stream()
                .sorted(Comparator.comparing(OcRecommendationVO::getRecommendScore).reversed())
                .limit(topN)
                .toList();
    }

    /**
     * 查找招募中的OC列表
     */
    public List<TornFactionOcDO> findRecrutList(long factionId, OcSlotDictBO joinedOcSlot) {
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
            calcOc.setReadyTime(calcOc.getReadyTime().minusDays(-1));
        } else {
            joinedOc.setReadyTime(joinedOc.getReadyTime().minusDays(-1));
            recruitOcList.add(joinedOc);
        }
        return recruitOcList;
    }

    /**
     * 查找招募中的OC空位
     */
    public List<TornFactionOcSlotDO> findEmptySlotList(List<TornFactionOcDO> recruitOcList,
                                                       OcSlotDictBO joinedOcSlot) {
        List<Long> recrutIdList = recruitOcList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(recrutIdList);
        if (joinedOcSlot != null) {
            emptySlotList.add(joinedOcSlot.getSlot());
        }


        return emptySlotList;
    }
}