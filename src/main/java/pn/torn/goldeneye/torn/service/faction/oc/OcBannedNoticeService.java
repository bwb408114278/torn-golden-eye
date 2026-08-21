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
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
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
 * <p>定时检测活动OC中的禁用和岗位成功率问题，并在帮派群内合并发送提醒消息。
 * 禁用检查覆盖全部级别OC，成功率检查仅覆盖{@value #PASS_RATE_CHECK_MIN_RANK}级及以上OC。</p>
 *
 * @author Bai
 * @version 1.3.9
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
    /**
     * 成功率检查最低OC级别（含），低于该级别的OC只检查禁用
     */
    private static final int PASS_RATE_CHECK_MIN_RANK = 7;

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

            // 禁用问题文案不区分OC，同一用户重复加入禁用OC时按文本去重
            entry.getValue().stream()
                    .map(this::formatIssue)
                    .distinct()
                    .forEach(text -> msgList.add(new TextQqMsg(text)));
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 构建成功率记录映射，同键重复记录仅保留首条并记录错误日志。
     *
     * @param factionId    帮派ID
     * @param passRateList 成功率记录列表
     * @return 以定位键索引的成功率映射
     */
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

    /**
     * 以用户、OC名称、级别和岗位短码构建成功率记录定位键。
     *
     * @param data 成功率记录
     * @return 定位键
     */
    private PassRateKey buildPassRateKey(TornFactionOcUserDO data) {
        return new PassRateKey(data.getUserId(), data.getOcName(), data.getRank(), data.getPosition());
    }

    /**
     * 扫描在岗岗位并收集巡检问题：禁用OC全部级别检查，成功率仅检查{@value #PASS_RATE_CHECK_MIN_RANK}级及以上OC。
     *
     * @param factionId     帮派ID
     * @param occupiedSlots 在岗岗位列表
     * @param ocMap         活动OC映射
     * @param passRateMap   成功率记录映射
     * @return 巡检问题列表
     */
    private List<InspectionIssue> findIssues(long factionId, List<TornFactionOcSlotDO> occupiedSlots,
                                             Map<Long, TornFactionOcDO> ocMap,
                                             Map<PassRateKey, TornFactionOcUserDO> passRateMap) {
        List<InspectionIssue> issues = new ArrayList<>();
        for (TornFactionOcSlotDO slot : occupiedSlots) {
            TornFactionOcDO oc = ocMap.get(slot.getOcId());
            if (oc == null) {
                continue;
            }
            InspectionIssue issue = inspectSlot(factionId, slot, oc, passRateMap);
            if (issue != null) {
                issues.add(issue);
            }
        }
        return issues;
    }

    /**
     * 检测单个在岗岗位：禁用OC直接产生禁用问题且不检查成功率，未禁用时仅{@value #PASS_RATE_CHECK_MIN_RANK}级及以上OC检查成功率。
     *
     * @param factionId   帮派ID
     * @param slot        岗位数据
     * @param oc          OC数据
     * @param passRateMap 成功率记录映射
     * @return 巡检问题，无问题时返回null
     */
    private InspectionIssue inspectSlot(long factionId, TornFactionOcSlotDO slot, TornFactionOcDO oc,
                                        Map<PassRateKey, TornFactionOcUserDO> passRateMap) {
        if (ocRecommendManager.isOcDisabled(factionId, oc)) {
            return InspectionIssue.ofDisabled(slot.getUserId());
        }
        return checkPassRate(factionId, slot, oc, passRateMap);
    }

    /**
     * 检查岗位成功率：无岗位要求时不检查，成功率为未知或低于帮派要求时产生问题。
     *
     * @param factionId   帮派ID
     * @param slot        岗位数据
     * @param oc          OC数据
     * @param passRateMap 成功率记录映射
     * @return 巡检问题，达到要求时返回null
     */
    private InspectionIssue checkPassRate(long factionId, TornFactionOcSlotDO slot, TornFactionOcDO oc,
                                          Map<PassRateKey, TornFactionOcUserDO> passRateMap) {
        if (!isPassRateCheckable(oc)) {
            return null;
        }
        TornSettingOcSlotDO requirement = ocRecommendManager.findSlotRequirement(factionId, oc, slot);
        Integer requiredPassRate = requirement == null ? null : requirement.getPassRate();
        if (requiredPassRate == null) {
            return null;
        }
        TornFactionOcUserDO passRateData = passRateMap.get(
                new PassRateKey(slot.getUserId(), oc.getName(), oc.getRank(), toSlotShortCode(slot.getPosition())));
        Integer actualPassRate = passRateData == null ? null : passRateData.getPassRate();
        if (actualPassRate == null) {
            log.warn("OC巡检成功率记录缺失, factionId={}, userId={}, ocId={}, ocName={}, rank={}, position={}",
                    factionId, slot.getUserId(), oc.getId(), oc.getName(), oc.getRank(), slot.getPosition());
        }
        if (actualPassRate == null || actualPassRate < requiredPassRate) {
            return new InspectionIssue(slot.getUserId(), slot.getPosition(), actualPassRate, requiredPassRate, false);
        }
        return null;
    }

    /**
     * 成功率检查是否覆盖该OC：仅{@value #PASS_RATE_CHECK_MIN_RANK}级及以上检查，低级别只检查禁用。
     */
    private boolean isPassRateCheckable(TornFactionOcDO oc) {
        return oc.getRank() != null && oc.getRank() >= PASS_RATE_CHECK_MIN_RANK;
    }

    private String toSlotShortCode(String position) {
        if (position == null) {
            return null;
        }
        int separatorIndex = position.indexOf('#');
        return separatorIndex < 0 ? position : position.substring(0, separatorIndex);
    }

    /**
     * 格式化单条巡检问题文本：禁用问题提示更换OC，成功率问题展示当前值与帮派要求。
     *
     * @param issue 巡检问题
     * @return 消息文本行
     */
    private String formatIssue(InspectionIssue issue) {
        if (issue.disabled()) {
            return " 你加入了禁用的OC, 需要更换其他OC\n";
        }
        return " 当前岗位" + issue.position() + "成功率: " + formatPassRate(issue.actualPassRate())
                + ", 帮派要求: " + issue.requiredPassRate() + "\n";
    }

    /**
     * 格式化成功率数值，无数据时显示未知。
     *
     * @param passRate 成功率
     * @return 展示文本
     */
    private String formatPassRate(Integer passRate) {
        return passRate == null ? "未知" : String.valueOf(passRate);
    }

    /**
     * 成功率记录定位键
     */
    private record PassRateKey(
            Long userId,
            String ocName,
            Integer rank,
            String position) {
    }

    /**
     * 巡检问题
     */
    private record InspectionIssue(
            Long userId,
            String position,
            Integer actualPassRate,
            Integer requiredPassRate,
            boolean disabled) {

        /**
         * 构建禁用OC问题，不携带成功率信息
         *
         * @param userId 用户ID
         * @return 禁用问题
         */
        static InspectionIssue ofDisabled(Long userId) {
            return new InspectionIssue(userId, null, null, null, true);
        }
    }
}
