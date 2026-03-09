package pn.torn.goldeneye.torn.manager.vip.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.torn.model.user.refill.TornUserRefillDTO;
import pn.torn.goldeneye.torn.model.user.refill.TornUserRefillVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * CD检查提醒策略类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.04
 */
@Component
@RequiredArgsConstructor
public class RefillEnergyNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeDAO noticeDao;
    /**
     * 仅在每天的 7、21 点执行检查
     */
    private static final Set<Integer> NOTIFY_HOURS = Set.of(7, 21);
    /**
     * 游戏日切换的小时(本地时区 8:00)
     */
    private static final int GAME_DAY_RESET_HOUR = 8;

    @Override
    public List<String> checkAndUpdate(VipNoticeDO notice, LocalDateTime checkTime) {
        int hour = checkTime.getHour();
        int minute = checkTime.getMinute();
        // 仅在指定整点的前 5 分钟窗口内触发
        if (!NOTIFY_HOURS.contains(hour) || minute >= 5) {
            return List.of();
        }

        LocalDate today = checkTime.toLocalDate();
        LocalDate gameDate = hour < GAME_DAY_RESET_HOUR ? today.minusDays(1) : today;
        // 当天游戏日已检查过且 Refill 已被使用，不再提醒
        if (gameDate.equals(notice.getLastRefillEnergyCheckDate()) && Boolean.TRUE.equals(notice.getIsRefillEnergy())) {
            return List.of();
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(notice.getUserId());
        if (key == null) {
            return List.of();
        }

        TornUserRefillVO resp = tornApi.sendRequest(new TornUserRefillDTO(), key, TornUserRefillVO.class);
        boolean isNotRefill = resp.getRefills().isEnergy();
        noticeDao.lambdaUpdate()
                .set(VipNoticeDO::getIsRefillEnergy, isNotRefill)
                .set(VipNoticeDO::getLastRefillEnergyCheckDate, gameDate)
                .eq(VipNoticeDO::getId, notice.getId())
                .update();
        if (isNotRefill) {
            return List.of();
        }

        String msg = hour < GAME_DAY_RESET_HOUR
                ? "Refill还有不到1小时就要重置了"
                : "今天还没Refill";
        return List.of(msg);
    }
}