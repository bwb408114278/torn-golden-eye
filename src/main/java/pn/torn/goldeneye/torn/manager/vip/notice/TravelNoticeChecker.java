package pn.torn.goldeneye.torn.manager.vip.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
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
 * @version 1.0.0
 * @since 2026.03.04
 */
@Component
@RequiredArgsConstructor
public class TravelNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeDAO noticeDao;

    @Override
    public List<String> checkAndUpdate(VipNoticeDO notice, LocalDateTime checkTime) {
        if (!shouldCallApi(checkTime, notice.getLastTravelCheckTime(), notice.getTravelAboard())) {
            return List.of();
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(notice.getUserId());
        if (key == null) {
            return List.of();
        }

        TornUserVO resp = tornApi.sendRequest(new TornUserDTO(), key, TornUserVO.class);
        if (TornUserStatusEnum.ABROAD.getCode().equals(resp.getProfile().getStatus().getState())) {
            noticeDao.lambdaUpdate()
                    .set(VipNoticeDO::getTravelAboard, 0L)
                    .set(VipNoticeDO::getLastTravelCheckTime, checkTime)
                    .eq(VipNoticeDO::getId, notice.getId())
                    .update();
            return List.of("在海外滞留了");
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
            noticeDao.lambdaUpdate()
                    .set(VipNoticeDO::getTravelAboard, nextCheckSecond)
                    .set(VipNoticeDO::getLastTravelCheckTime, checkTime)
                    .eq(VipNoticeDO::getId, notice.getId())
                    .update();
            return List.of();
        } else {
            noticeDao.lambdaUpdate()
                    .set(VipNoticeDO::getTravelAboard, 600)
                    .set(VipNoticeDO::getLastTravelCheckTime, checkTime)
                    .eq(VipNoticeDO::getId, notice.getId())
                    .update();
            return List.of();
        }
    }
}