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
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDTO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CD检查提醒策略类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.02.13
 */
@Component
@RequiredArgsConstructor
public class CooldownNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeStateDAO stateDao;

    @Override
    public List<VipNoticeTypeEnum> getType() {
        return List.of(VipNoticeTypeEnum.DRUG, VipNoticeTypeEnum.BOOSTER);
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

        TornUserCooldownVO resp = tornApi.sendRequest(new TornUserCooldownDTO(), key, TornUserCooldownVO.class);
        int drugCd = resp.getCooldowns().getDrug();
        int boosterCd = resp.getCooldowns().getBooster();
        for (VipNoticeStateDO state : stateList) {
            int lastValue = state.getNoticeType().equals(VipNoticeTypeEnum.DRUG.getBit()) ? drugCd : boosterCd;
            stateDao.lambdaUpdate()
                    .set(VipNoticeStateDO::getLastValue, lastValue)
                    .set(VipNoticeStateDO::getLastCheckTime, checkTime)
                    .eq(VipNoticeStateDO::getId, state.getId())
                    .update();
        }

        List<String> messages = new ArrayList<>();
        if (drugCd == 0) {
            messages.add("大郎, 该吃药了");
        }
        if (boosterCd == 0) {
            messages.add("Booster CD空了");
        }

        return messages;
    }
}