package pn.torn.goldeneye.torn.service.faction.oc.notice;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础OC通知消息类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.17
 */
public abstract class BaseTornFactionOcNoticeService {
    @Resource
    protected TornFactionOcManager ocManager;
    @Resource
    protected TornFactionOcSlotDAO slotDao;

    /**
     * 获取招募中列表
     */
    protected List<TornFactionOcDO> findRecList(TornFactionOcNoticeBO param) {
        String excludePlanKey = TornConstants.SETTING_KEY_OC_PLAN_ID + param.excludeRank();
        String excludeRecKey = TornConstants.SETTING_KEY_OC_REC_ID + param.excludeRank();
        return ocManager.queryRotationRecruitList(param.planId(), TornConstants.FACTION_PN_ID,
                excludePlanKey, excludeRecKey, param.enableRank());
    }

    /**
     * 构建招募中队伍Map
     */
    protected Map<TornFactionOcDO, List<TornFactionOcSlotDO>> buildLackMap(List<TornFactionOcDO> recList) {
        Map<Long, List<TornFactionOcSlotDO>> slotMap = slotDao.queryMapByOc(recList);
        Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap = HashMap.newHashMap(recList.size());
        for (TornFactionOcDO oc : recList) {
            if (!oc.getReadyTime().toLocalDate().isAfter(LocalDate.now())) {
                lackMap.put(oc, slotMap.get(oc.getId()));
            }
        }

        return lackMap;
    }

    /**
     * 构建级别描述
     */
    protected String buildRankDesc(TornFactionOcNoticeBO param) {
        return String.join("/", Arrays.stream(param.enableRank()).boxed().map(Object::toString).toList());
    }
}