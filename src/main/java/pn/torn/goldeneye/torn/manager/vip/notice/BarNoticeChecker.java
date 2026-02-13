package pn.torn.goldeneye.torn.manager.vip.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarDTO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bar提醒检查基础策略类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.13
 */
@Component
@RequiredArgsConstructor
public class BarNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeDAO noticeDao;

    @Override
    public List<String> checkAndUpdate(VipNoticeDO notice, LocalDateTime checkTime) {
        boolean energyNeed = shouldCallApi(checkTime, notice.getLastBarCheckTime(), notice.getEnergyFull());
        boolean nerveNeed = shouldCallApi(checkTime, notice.getLastBarCheckTime(), notice.getNerveFull());
        if (!energyNeed && !nerveNeed) {
            return List.of();
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(notice.getUserId());
        if (key == null) {
            return List.of();
        }

        TornUserBarVO resp = tornApi.sendRequest(new TornUserBarDTO(), key, TornUserBarVO.class);
        int energyFull = resp.getBars().getEnergy().getFullTime();
        int nerveFull = resp.getBars().getNerve().getFullTime();
        noticeDao.lambdaUpdate()
                .set(VipNoticeDO::getEnergyFull, energyFull)
                .set(VipNoticeDO::getNerveFull, nerveFull)
                .set(VipNoticeDO::getLastBarCheckTime, checkTime)
                .eq(VipNoticeDO::getId, notice.getId())
                .update();

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
