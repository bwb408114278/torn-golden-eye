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
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarDTO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bar提醒检查策略类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.02.13
 */
@Component
@RequiredArgsConstructor
public class BarNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeStateDAO stateDao;

    @Override
    public List<VipNoticeTypeEnum> getType() {
        return List.of(VipNoticeTypeEnum.ENERGY, VipNoticeTypeEnum.NERVE);
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

        TornUserBarVO resp = tornApi.sendRequest(new TornUserBarDTO(), key, TornUserBarVO.class);
        int energyFull = resp.getBars().getEnergy().getFullTime();
        int nerveFull = resp.getBars().getNerve().getFullTime();
        for (VipNoticeStateDO state : stateList) {
            int lastValue = state.getNoticeType().equals(VipNoticeTypeEnum.ENERGY.getBit()) ? energyFull : nerveFull;
            stateDao.lambdaUpdate()
                    .set(VipNoticeStateDO::getLastValue, lastValue)
                    .set(VipNoticeStateDO::getLastCheckTime, checkTime)
                    .eq(VipNoticeStateDO::getId, state.getId())
                    .update();
        }

        List<String> messages = new ArrayList<>();
        if (energyFull == 0) {
            messages.add("Energy满了");
        }
        if (nerveFull == 0) {
            messages.add("Nerve满了");
        }
        return messages;
    }
}