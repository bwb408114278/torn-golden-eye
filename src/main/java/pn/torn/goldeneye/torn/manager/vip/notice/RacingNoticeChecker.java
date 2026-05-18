package pn.torn.goldeneye.torn.manager.vip.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeStateDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.torn.model.user.racing.TornUserRaceDTO;
import pn.torn.goldeneye.torn.model.user.racing.TornUserRacesVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 赛车提醒检查策略类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
//@Component
//@RequiredArgsConstructor
//public class RacingNoticeChecker extends BaseVipNoticeChecker {
//    private final TornApi tornApi;
//    private final TornApiKeyConfig apiKeyConfig;
//    private final VipNoticeStateDAO stateDao;
//
//    @Override
//    public List<VipNoticeTypeEnum> getType() {
//        return List.of(VipNoticeTypeEnum.RACING);
//    }
//
//    @Override
//    public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList,
//                                       LocalDateTime checkTime) {
//        if (checkDontNotice(stateList, checkTime)) {
//            return List.of();
//        }
//
//        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(config.getUserId());
//        if (key == null) {
//            return List.of();
//        }
//
//        TornUserRacesVO resp = tornApi.sendRequest(new TornUserRaceDTO(), key, TornUserRacesVO.class);
//        TornRaceDetailVO first = resp.getRaces().getFirst();
//        long nextCheckTimestamp = first.getSchedule().getEnd() == null ?
//                first.getSchedule().getJoinUntil() : first.getSchedule().getEnd();
//        LocalDateTime nextCheckTime = DateTimeUtils.convertToDateTime(nextCheckTimestamp);
//        long nextCheckDuration = nextCheckTime.isAfter(checkTime) ?
//                Duration.between(checkTime, nextCheckTime).toSeconds() : 0;
//
//        VipNoticeStateDO state = stateList.getFirst();
//        stateDao.lambdaUpdate()
//                .set(VipNoticeStateDO::getLastValue, nextCheckDuration)
//                .set(VipNoticeStateDO::getLastCheckTime, checkTime)
//                .eq(VipNoticeStateDO::getId, state.getId())
//                .update();
//
//        if ("finished".equals(first.getStatus()) || nextCheckDuration == 0) {
//            return List.of("可以参加新的赛车了");
//        }
//
//        return List.of();
//    }
//}