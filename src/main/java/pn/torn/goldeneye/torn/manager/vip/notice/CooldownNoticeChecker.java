package pn.torn.goldeneye.torn.manager.vip.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDTO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CD提醒检查基础策略类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.13
 */
@Component
@RequiredArgsConstructor
public class CooldownNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeDAO noticeDao;

    @Override
    public List<String> checkAndUpdate(VipNoticeDO notice, LocalDateTime checkTime) {
        if (!shouldCallApi(checkTime, notice.getLastCdCheckTime(), notice.getDrugCd())) {
            return List.of();
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(notice.getUserId());
        if (key == null) {
            return List.of();
        }

        TornUserCooldownVO resp = tornApi.sendRequest(new TornUserCooldownDTO(), key, TornUserCooldownVO.class);
        int drugCd = resp.getCooldowns().getDrug();
        noticeDao.lambdaUpdate()
                .set(VipNoticeDO::getDrugCd, drugCd)
                .set(VipNoticeDO::getLastCdCheckTime, checkTime)
                .eq(VipNoticeDO::getId, notice.getId())
                .update();

        return drugCd == 0 ? List.of("大郎, 该吃药了") : List.of();
    }
}
