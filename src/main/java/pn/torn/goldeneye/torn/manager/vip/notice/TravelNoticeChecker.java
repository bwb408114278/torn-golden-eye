package pn.torn.goldeneye.torn.manager.vip.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeStateDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.travel.TornUserTravelDTO;
import pn.torn.goldeneye.torn.model.user.travel.TornUserTravelVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 躺飞提醒基础策略类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.03.04
 */
@Component
@RequiredArgsConstructor
public class TravelNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeStateDAO stateDao;

    @Override
    public List<VipNoticeTypeEnum> getType() {
        return List.of(VipNoticeTypeEnum.TRAVEL);
    }

    @Override
    public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList,
                                       LocalDateTime checkTime) {
        if (checkDontNotice(stateList, checkTime)) {
            return List.of();
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(config.getUserId());
        if (key == null) {
            return List.of();
        }

        TornUserVO resp = tornApi.sendRequest(new TornUserDTO(), key, TornUserVO.class);
        VipNoticeStateDO state = stateList.getFirst();
        if (TornUserStatusEnum.ABROAD.getCode().equals(resp.getProfile().getStatus().getState())) {
            stateDao.lambdaUpdate()
                    .set(VipNoticeStateDO::getLastValue, 0L)
                    .set(VipNoticeStateDO::getLastCheckTime, checkTime)
                    .eq(VipNoticeStateDO::getId, state.getId())
                    .update();
            boolean isTravelPause = config.getPauseTravelUntil() != null &&
                    !LocalDateTime.now().isAfter(config.getPauseTravelUntil());
            return isTravelPause ? List.of() : List.of("在海外滞留了");
        } else if (TornUserStatusEnum.TRAVELING.getCode().equals(resp.getProfile().getStatus().getState())) {
            TornUserTravelVO travel = tornApi.sendRequest(new TornUserTravelDTO(), key, TornUserTravelVO.class);
            LocalDateTime arrivalTime = DateTimeUtils.convertToDateTime(travel.getTravel().getArrivalAt());
            long nextCheckSecond = Duration.between(checkTime, arrivalTime).toSeconds();
            if (nextCheckSecond < 0) {
                nextCheckSecond = 0L;
            }

            if ("Torn".equals(travel.getTravel().getDestination())) {
                nextCheckSecond += 600L;
            }
            stateDao.lambdaUpdate()
                    .set(VipNoticeStateDO::getLastValue, nextCheckSecond)
                    .set(VipNoticeStateDO::getLastCheckTime, checkTime)
                    .eq(VipNoticeStateDO::getId, state.getId())
                    .update();
            return List.of();
        } else {
            stateDao.lambdaUpdate()
                    .set(VipNoticeStateDO::getLastValue, 600)
                    .set(VipNoticeStateDO::getLastCheckTime, checkTime)
                    .eq(VipNoticeStateDO::getId, state.getId())
                    .update();
            return List.of();
        }
    }
}