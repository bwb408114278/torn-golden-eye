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
import pn.torn.goldeneye.torn.model.user.refill.TornUserRefillDTO;
import pn.torn.goldeneye.torn.model.user.refill.TornUserRefillVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * CD检查提醒策略类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.03.04
 */
@Component
@RequiredArgsConstructor
public class RefillEnergyNoticeChecker extends BaseVipNoticeChecker {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipNoticeStateDAO stateDao;
    /**
     * 仅在每天的 7、21 点执行检查
     */
    private static final Set<Integer> NOTIFY_HOURS = Set.of(7, 21);
    /**
     * 游戏日切换的小时(本地时区 8:00)
     */
    private static final int GAME_DAY_RESET_HOUR = 8;

    @Override
    public List<VipNoticeTypeEnum> getType() {
        return List.of(VipNoticeTypeEnum.REFILL);
    }

    @Override
    public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList,
                                       LocalDateTime checkTime) {
        int hour = checkTime.getHour();
        int minute = checkTime.getMinute();
        // 仅在指定整点的前 5 分钟窗口内触发
        if (!NOTIFY_HOURS.contains(hour) || minute >= 5) {
            return List.of();
        }

        VipNoticeStateDO state = stateList.getFirst();
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        if (state.getLastValue() > now) {
            return List.of();
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(config.getUserId());
        if (key == null) {
            return List.of();
        }

        TornUserRefillVO resp = tornApi.sendRequest(new TornUserRefillDTO(), key, TornUserRefillVO.class);
        boolean isRefill = resp.getRefills().isEnergy();
        // 计算下次游戏日重置时间（次日 8:00）作为解禁时间
        LocalDate today = checkTime.toLocalDate();
        LocalDate gameDate = hour < GAME_DAY_RESET_HOUR ? today : today.plusDays(1);
        long nextResetEpoch = gameDate.atTime(GAME_DAY_RESET_HOUR, 0).toEpochSecond(ZoneOffset.UTC);
        // 已 Refill → 记录解禁时间，今天不再提醒；未 Refill → lastValue 置 0，下次窗口继续提醒
        stateDao.lambdaUpdate()
                .set(VipNoticeStateDO::getLastValue, isRefill ? nextResetEpoch : 0L)
                .set(VipNoticeStateDO::getLastCheckTime, checkTime)
                .eq(VipNoticeStateDO::getId, state.getId())
                .update();

        if (isRefill) {
            return List.of();
        }

        String msg = hour < GAME_DAY_RESET_HOUR
                ? "Refill还有不到1小时就要重置了"
                : "今天还没Refill";
        return List.of(msg);
    }
}