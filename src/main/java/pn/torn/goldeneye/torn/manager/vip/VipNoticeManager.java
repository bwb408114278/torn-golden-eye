package pn.torn.goldeneye.torn.manager.vip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDTO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * VIP提醒公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VipNoticeManager {
    /**
     * 当drugCd为0时，多久重新查询一次API（分钟），用于检测用户是否已吃药
     */
    private static final long RECHECK_MINUTES_WHEN_CD_ZERO = 30;
    /**
     * 免打扰开始小时（含）
     */
    private static final int QUIET_HOUR_START = 0;
    /**
     * 免打扰结束小时（不含）
     */
    private static final int QUIET_HOUR_END = 7;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final Bot bot;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final VipSubscribeDAO subscribeDao;
    private final VipNoticeDAO noticeDao;
    private final ProjectProperty projectProperty;

    /**
     * VIP CD提醒
     */
    @Scheduled(cron = "10 */5 * * * ?")
    @SuppressWarnings("ConstantValue")
    public void vipCooldownNotice() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }
        // 静默时间
        int hour = LocalTime.now().getHour();
        // 免打扰时段判断，保留 QUIET_HOUR_START 比较以便后续调整时段
        if (hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END) {
            return;
        }

        LocalDate today = LocalDate.now();
        List<VipSubscribeDO> vipList = subscribeDao.lambdaQuery().ge(VipSubscribeDO::getEndDate, today).list();
        List<Long> userIdList = vipList.stream().map(VipSubscribeDO::getUserId).toList();
        List<VipNoticeDO> noticeList = noticeDao.lambdaQuery().in(VipNoticeDO::getUserId, userIdList).list();

        LocalDateTime checkTime = LocalDateTime.now();
        List<Long> noticeIdList = new ArrayList<>();
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (VipSubscribeDO vip : vipList) {
            futureList.add(CompletableFuture.runAsync(() -> {
                        if (checkUserCooldown(vip.getUserId(), noticeList, checkTime)) {
                            noticeIdList.add(vip.getQqId());
                        }
                    },
                    virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        if (!noticeIdList.isEmpty()) {
            GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getVipGroupId())
                    .addMsg(new TextQqMsg("大郎, 该吃药了"));
            noticeIdList.forEach(i -> builder.addMsg(new TextQqMsg("\n")).addMsg(new AtQqMsg(i)));
            bot.sendRequest(builder.build(), String.class);
        }
    }

    /**
     * 校验用户的CD是否该提醒了
     *
     * @return true为该提醒
     */
    boolean checkUserCooldown(long userId, List<VipNoticeDO> noticeList, LocalDateTime checkTime) {
        VipNoticeDO notice = noticeList.stream()
                .filter(n -> n.getUserId().equals(userId))
                .findAny().orElse(null);
        if (checkNeedNotice(checkTime, notice)) {
            return false;
        }

        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(userId);
        if (key == null) {
            return false;
        }

        TornUserCooldownVO resp = tornApi.sendRequest(new TornUserCooldownDTO(), key, TornUserCooldownVO.class);
        long drugCd = resp.getCooldowns().getDrug();
        VipNoticeDO data = new VipNoticeDO(userId, checkTime, drugCd);
        if (notice == null) {
            noticeDao.save(data);
        } else {
            data.setId(notice.getId());
            noticeDao.updateById(data);
        }

        // 关键逻辑：只在drugCd从非0变为0时提醒（状态转换），避免重复提醒
        // notice == null: 首次检查就是0，也提醒
        // notice.getDrugCd() != 0: 上次不是0，现在是0，说明CD刚好转完，提醒
        boolean previousWasZero = notice != null && notice.getDrugCd().equals(0L);
        return drugCd == 0L && !previousWasZero;
    }

    /**
     * 判断是否需要提醒
     *
     * @param checkTime 本次检查时间
     * @return true为需要
     */
    private boolean checkNeedNotice(LocalDateTime checkTime, VipNoticeDO notice) {
        // 判断是否需要调用API重新查询
        boolean shouldCheck;
        if (notice == null) {
            // 首次检查
            shouldCheck = true;
        } else if (notice.getDrugCd().equals(0L)) {
            // 上次drugCd为0，定期重新检查（看用户是否已经吃了药）
            shouldCheck = notice.getCheckTime()
                    .plusMinutes(RECHECK_MINUTES_WHEN_CD_ZERO)
                    .isBefore(checkTime);
        } else {
            // 上次drugCd > 0，CD过期后重新检查
            shouldCheck = notice.getCheckTime()
                    .plusSeconds(notice.getDrugCd())
                    .isBefore(checkTime);
        }

        return !shouldCheck;
    }
}