package pn.torn.goldeneye.torn.manager.vip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;
import pn.torn.goldeneye.torn.manager.vip.notice.VipNoticeChecker;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * VIP提醒公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VipNoticeManager {
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
    private final List<VipNoticeChecker> checkerList;
    private final VipSubscribeDAO subscribeDao;
    private final VipNoticeDAO noticeDao;
    private final ProjectProperty projectProperty;

    /**
     * 开始执行提醒任务
     */
    @Scheduled(cron = "10 */5 * * * ?")
    @SuppressWarnings("ConstantValue")
    public void notice() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }
        // 静默时间
        int hour = LocalTime.now().getHour();
        // 免打扰时段判断，保留 QUIET_HOUR_START 比较以便后续调整时段
        if (hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<VipSubscribeDO> vipList = subscribeDao.lambdaQuery().ge(VipSubscribeDO::getEndDate, now.toLocalDate()).list();
        if (CollectionUtils.isEmpty(vipList)) {
            return;
        }

        List<Long> userIdList = vipList.stream().map(VipSubscribeDO::getUserId).toList();
        List<VipNoticeDO> noticeList = noticeDao.lambdaQuery().in(VipNoticeDO::getUserId, userIdList).list();

        Map<String, ConcurrentLinkedQueue<Long>> resultMap = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futureList = vipList.stream()
                .map(vip -> CompletableFuture.runAsync(() -> {
                    try {
                        processUserMsg(vip, noticeList, now, resultMap);
                    } catch (Exception e) {
                        log.error("检查用户 {} 提醒时异常", vip.getUserId(), e);
                    }
                }, virtualThreadExecutor))
                .toList();

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        resultMap.forEach((message, qqIds) -> sendNoticeMsg(message, List.copyOf(qqIds)));
    }

    /**
     * 处理用户的通知消息
     */
    private void processUserMsg(VipSubscribeDO vip, List<VipNoticeDO> noticeList,
                                LocalDateTime checkTime, Map<String, ConcurrentLinkedQueue<Long>> msgMap) {
        VipNoticeDO notice = getOrCreateNotice(vip, noticeList);
        for (VipNoticeChecker checker : checkerList) {
            List<String> messages = checker.checkAndUpdate(notice, checkTime);
            for (String msg : messages) {
                msgMap.computeIfAbsent(msg, k -> new ConcurrentLinkedQueue<>()).add(vip.getQqId());
            }
        }
    }

    /**
     * 获取或者创建通知数据
     */
    private VipNoticeDO getOrCreateNotice(VipSubscribeDO vip, List<VipNoticeDO> noticeList) {
        return noticeList.stream()
                .filter(n -> n.getUserId().equals(vip.getUserId()))
                .findAny()
                .orElseGet(() -> {
                    VipNoticeDO newNotice = new VipNoticeDO(vip.getUserId());
                    noticeDao.save(newNotice);
                    return newNotice;
                });
    }

    /**
     * 发送通知消息
     */
    private void sendNoticeMsg(String msgText, List<Long> noticeIdList) {
        if (CollectionUtils.isEmpty(noticeIdList)) {
            return;
        }

        GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipGroupId())
                .addMsg(new TextQqMsg(msgText));
        noticeIdList.forEach(i -> builder.addMsg(new TextQqMsg("\n")).addMsg(new AtQqMsg(i)));
        bot.sendRequest(builder.build(), String.class);
    }
}