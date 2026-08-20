package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OC巡检提醒逻辑层。
 * <p>定时检测活动OC中的禁用和岗位成功率问题，并在帮派群内合并发送提醒消息。</p>
 *
 * @author Bai
 * @version 1.3.6
 * @since 2026.07.17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcBannedNoticeService {
    /**
     * 静默开始小时（含）
     */
    private static final int QUIET_HOUR_START = 0;
    /**
     * 静默结束小时（不含）
     */
    private static final int QUIET_HOUR_END = 6;

    private final Bot bot;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcUserDAO ocUserDao;
    private final TornUserDAO userDao;
    private final TornSettingFactionManager settingFactionManager;
    private final TornOcRecommendManager ocRecommendManager;

    /**
     * 扫描所有帮派，检测并提醒活动OC中的问题成员。
     *
     * @param checkTime 检测时间
     */
    public void checkAndNotice(LocalDateTime checkTime) {
        int hour = checkTime.getHour();
        if (hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END) {
            return;
        }

        List<TornSettingFactionDO> factionList = settingFactionManager.getList();
        for (TornSettingFactionDO faction : factionList) {
            if (faction.getGroupId().equals(0L)) {
                continue;
            }
            try {
                checkFaction(faction);
            } catch (Exception e) {
                log.error("禁止OC加入提醒检测失败, factionId={}", faction.getId(), e);
            }
        }
    }

    /**
     * 检测单个帮派并发送提醒。
     *
     * @param faction 帮派设置
     */
    private void checkFaction(TornSettingFactionDO faction) {
        List<TornFactionOcDO> activeOcList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, faction.getId())
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(),
                        TornOcStatusEnum.PLANNING.getCode())
                .list();
        if (CollectionUtils.isEmpty(activeOcList)) {
            return;
        }

        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(activeOcList);
        List<TornFactionOcSlotDO> occupiedSlots = slotList.stream()
                .filter(slot -> slot.getUserId() != null)
                .toList();
        if (occupiedSlots.isEmpty()) {
            return;
        }

        List<Long> userIdList = occupiedSlots.stream()
                .map(TornFactionOcSlotDO::getUserId)
                .distinct()
                .toList();
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdList);
        List<TornFactionOcUserDO> passRateList = ocUserDao.queryByFactionIdAndUserIds(faction.getId(), userIdList);
        Map<PassRateKey, TornFactionOcUserDO> passRateMap = buildPassRateMap(faction.getId(), passRateList);
        Map<Long, TornFactionOcDO> ocMap = activeOcList.stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, oc -> oc));
        List<InspectionIssue> issues = findIssues(faction.getId(), occupiedSlots, ocMap, passRateMap);
        if (issues.isEmpty()) {
            return;
        }
        sendInspectionNotice(faction, issues, userMap);
    }

    /**
     * 构建并发送OC巡检提醒消息。
     *
     * @param faction 帮派设置
     * @param issues  巡检问题
     * @param userMap 用户映射
     */
    private void sendInspectionNotice(TornSettingFactionDO faction, List<InspectionIssue> issues,
                                      Map<Long, TornUserDO> userMap) {
        List<QqMsgParam<?>> msgList = new ArrayList<>();

        // @OC指挥官
        List<Long> commanderIds = NumberUtils.splitToLongList(faction.getOcCommanderIds());
        for (Long qqId : commanderIds) {
            msgList.add(new AtQqMsg(qqId));
        }

        msgList.add(new TextQqMsg("\n帮派OC巡检提醒：\n\n"));

        // 按用户聚合，同一用户只@一次
        Map<Long, List<InspectionIssue>> issuesByUser = issues.stream()
                .collect(Collectors.groupingBy(InspectionIssue::userId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Long, List<InspectionIssue>> entry : issuesByUser.entrySet()) {
            Long userId = entry.getKey();
            TornUserDO user = userMap.get(userId);
            String displayName = user != null ? user.getNickname() : String.valueOf(userId);

            // 同一用户只@一次，后续行仅展示文本
            if (user != null && !user.getQqId().equals(0L)) {
                msgList.add(new AtQqMsg(user.getQqId()));
            } else {
                msgList.add(new TextQqMsg(displayName + " "));
            }

            for (InspectionIssue issue : entry.getValue()) {
                msgList.add(new TextQqMsg(formatIssue(issue)));
            }
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }

    private Map<PassRateKey, TornFactionOcUserDO> buildPassRateMap(long factionId,
                                                                   List<TornFactionOcUserDO> passRateList) {
        if (CollectionUtils.isEmpty(passRateList)) {
            return Map.of();
        }

        Map<PassRateKey, List<TornFactionOcUserDO>> grouped = passRateList.stream()
                .collect(Collectors.groupingBy(this::buildPassRateKey));
        Map<PassRateKey, TornFactionOcUserDO> result = new LinkedHashMap<>();
        grouped.forEach((key, values) -> {
            if (values.size() > 1) {
                log.error("OC巡检成功率记录重复, factionId={}, userId={}, ocName={}, rank={}, position={}",
                        factionId, key.userId(), key.ocName(), key.rank(), key.position());
                return;
            }
            result.put(key, values.getFirst());
        });
        return result;
    }

    private PassRateKey buildPassRateKey(TornFactionOcUserDO data) {
        return new PassRateKey(data.getUserId(), data.getOcName(), data.getRank(), data.getPosition());
    }

    private List<InspectionIssue> findIssues(long factionId, List<TornFactionOcSlotDO> occupiedSlots,
                                             Map<Long, TornFactionOcDO> ocMap,
                                             Map<PassRateKey, TornFactionOcUserDO> passRateMap) {
        List<InspectionIssue> issues = new ArrayList<>();
        for (TornFactionOcSlotDO slot : occupiedSlots) {
            TornFactionOcDO oc = ocMap.get(slot.getOcId());
            if (oc == null) {
                continue;
            }

            boolean disabled = ocRecommendManager.isOcDisabled(factionId, oc);
            var requirement = ocRecommendManager.findSlotRequirement(factionId, oc, slot);
            TornFactionOcUserDO passRateData = passRateMap.get(
                    new PassRateKey(slot.getUserId(), oc.getName(), oc.getRank(), toSlotShortCode(slot.getPosition())));
            Integer actualPassRate = passRateData == null ? null : passRateData.getPassRate();
            Integer requiredPassRate = requirement == null ? null : requirement.getPassRate();
            boolean passRateInsufficient = isPassRateInsufficient(actualPassRate, requiredPassRate);
            if (requirement != null && (passRateData == null || actualPassRate == null)) {
                log.warn("OC巡检成功率记录缺失, factionId={}, userId={}, ocId={}, ocName={}, rank={}, position={}",
                        factionId, slot.getUserId(), oc.getId(), oc.getName(), oc.getRank(), slot.getPosition());
            }
            if (disabled || passRateInsufficient) {
                issues.add(new InspectionIssue(slot.getUserId(), oc.getId(), oc.getName(), oc.getRank(),
                        slot.getPosition(), actualPassRate, requiredPassRate, disabled, passRateInsufficient));
            }
        }
        return issues;
    }

    private boolean isPassRateInsufficient(Integer actualPassRate, Integer requiredPassRate) {
        return actualPassRate != null && requiredPassRate != null && actualPassRate < requiredPassRate;
    }

    private String toSlotShortCode(String position) {
        if (position == null) {
            return null;
        }
        int separatorIndex = position.indexOf('#');
        return separatorIndex < 0 ? position : position.substring(0, separatorIndex);
    }

    private String formatIssue(InspectionIssue issue) {
        StringBuilder builder = new StringBuilder(" OC：")
                .append(issue.ocName())
                .append("（级别：")
                .append(issue.rank())
                .append("），岗位：")
                .append(issue.position());
        if (issue.disabled()) {
            builder.append("，问题：OC已禁用");
        }
        if (issue.passRateInsufficient()) {
            builder.append("，成功率不足：当前")
                    .append(issue.actualPassRate())
                    .append("%，要求")
                    .append(issue.requiredPassRate())
                    .append("%");
        } else if (issue.disabled()) {
            builder.append("，成功率：当前")
                    .append(formatPassRate(issue.actualPassRate()))
                    .append("%，要求")
                    .append(formatPassRate(issue.requiredPassRate()))
                    .append("%");
        }
        return builder.append("\n").toString();
    }

    private String formatPassRate(Integer passRate) {
        return passRate == null ? "未知" : String.valueOf(passRate);
    }

    private record PassRateKey(Long userId, String ocName, Integer rank, String position) {
    }

    private record InspectionIssue(Long userId, Long ocId, String ocName, Integer rank, String position,
                                   Integer actualPassRate, Integer requiredPassRate, boolean disabled,
                                   boolean passRateInsufficient) {
    }
}
