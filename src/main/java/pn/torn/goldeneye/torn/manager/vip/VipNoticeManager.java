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
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeConfigDAO;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeStateDAO;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.user.TornQqUserManager;
import pn.torn.goldeneye.torn.manager.vip.notice.VipNoticeChecker;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * VIP提醒公共逻辑层
 *
 * @author Bai
 * @version 1.1.3
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
    private static final int QUIET_HOUR_END = 6;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final Bot bot;
    private final List<VipNoticeChecker> checkerList;
    private final TornQqUserManager qqUserManager;
    private final SysSettingManager sysSettingManager;
    private final VipSubscribeDAO subscribeDao;
    private final VipNoticeConfigDAO noticeConfigDao;
    private final VipNoticeStateDAO noticeStateDao;
    private final ProjectProperty projectProperty;

    /**
     * 开始执行提醒任务
     */
    @Scheduled(cron = "10 */5 * * * ?")
    public void notice() {
        String isNotice = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_NOTICE);
        if (!"true".equalsIgnoreCase(isNotice) || !BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }
        // 静默时间
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();
        // 静默时段（0:00-5:59）每小时只执行一次：仅在整点执行
        if (hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END && minute != 0) {
            return;
        }
        // 8:00 跳过本次提醒, 因为Api Key还未更新
        if (hour == 8 && minute == 0) {
            return;
        }

        List<VipSubscribeDO> vipList = subscribeDao.lambdaQuery().ge(VipSubscribeDO::getEndDate, now.toLocalDate()).list();
        if (CollectionUtils.isEmpty(vipList)) {
            return;
        }

        Map<String, Set<Long>> resultMap = new ConcurrentHashMap<>();
        processUserMsg(vipList, now, resultMap);
        resultMap.forEach((message, qqIds) -> sendNoticeMsg(message, List.copyOf(qqIds)));
    }

    /**
     * 处理用户的通知消息
     */
    private void processUserMsg(List<VipSubscribeDO> vipList, LocalDateTime checkTime,
                                Map<String, Set<Long>> msgMap) {
        List<Long> userIdList = vipList.stream().map(VipSubscribeDO::getUserId).distinct().toList();
        List<VipNoticeConfigDO> configList = noticeConfigDao.lambdaQuery()
                .in(VipNoticeConfigDO::getUserId, userIdList)
                .list();
        List<VipNoticeStateDO> stateList = noticeStateDao.lambdaQuery()
                .in(VipNoticeStateDO::getUserId, userIdList)
                .list();
        Map<Long, Map<Integer, List<VipNoticeStateDO>>> stateGroupMap = stateList.stream()
                .collect(Collectors.groupingBy(VipNoticeStateDO::getUserId,
                        Collectors.groupingBy(VipNoticeStateDO::getNoticeType)));
        List<CompletableFuture<Void>> futureList = configList.stream()
                .map(config -> CompletableFuture.runAsync(() -> {
                    try {
                        checkAndBuildMsg(config, stateGroupMap, checkTime, msgMap);
                    } catch (Exception e) {
                        log.error("处理VIP提醒失败, userId={}", config.getUserId(), e);
                    }
                }, virtualThreadExecutor))
                .toList();
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 检查并构建用户的提醒信息
     *
     * @param msgMap Key为消息内容, Value为该消息类型下需要提醒的用户ID
     */
    private void checkAndBuildMsg(VipNoticeConfigDO config, Map<Long, Map<Integer, List<VipNoticeStateDO>>> stateGroupMap,
                                  LocalDateTime checkTime, Map<String, Set<Long>> msgMap) {
        Map<Integer, List<VipNoticeStateDO>> userStateMap = stateGroupMap.getOrDefault(config.getUserId(), Map.of());
        for (VipNoticeChecker checker : checkerList) {
            List<VipNoticeTypeEnum> type = checker.getType();
            // 跳过禁用的类型
            if (!config.isEnabled(type)) {
                continue;
            }

            List<VipNoticeStateDO> checkTypeList = type.stream()
                    .map(VipNoticeTypeEnum::getBit)
                    .map(userStateMap::get)
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .toList();
            List<String> messages = checker.checkAndUpdate(config, checkTypeList, checkTime);
            for (String msg : messages) {
                msgMap.computeIfAbsent(msg, k -> ConcurrentHashMap.newKeySet()).add(config.getQqId());
            }
        }
    }

    /**
     * 发送通知消息
     */
    private void sendNoticeMsg(String msgText, List<Long> noticeIdList) {
        if (CollectionUtils.isEmpty(noticeIdList)) {
            return;
        }

        Set<Long> qqIdSet = new HashSet<>(qqUserManager.getGroupQqIdList(projectProperty.getVipNoticeGroupId()));
        List<Long> existsQqIdList = noticeIdList.stream().filter(qqIdSet::contains).toList();
        if (CollectionUtils.isEmpty(existsQqIdList)) {
            return;
        }

        GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipNoticeGroupId())
                .addMsg(new TextQqMsg(msgText));
        existsQqIdList.forEach(i -> builder.addMsg(new TextQqMsg("\n")).addMsg(new AtQqMsg(i)));
        bot.sendRequest(builder.build(), String.class);
    }
}