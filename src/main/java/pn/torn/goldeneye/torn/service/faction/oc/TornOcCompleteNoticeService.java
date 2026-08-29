package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
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
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.faction.crime.*;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcAssignService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC完成通知逻辑层
 *
 * @author Bai
 * @version 1.5.1
 * @since 2025.11.26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcCompleteNoticeService {
    private final Bot bot;
    private final TornApi tornApi;
    private final DynamicTaskService taskService;
    private final TornOcAssignService assignService;
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornItemsManager itemsManager;
    private final TornFactionOcMsgManager msgManager;
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;
    private final TornUserDAO userDao;
    // 时间窗口: 3分钟内完成的OC合并通知
    private static final int TIME_WINDOW_MINUTES = 3;
    // Torn OC在准备时间所在分钟的下一分钟统一执行
    private static final int OC_PLANNED_START_OFFSET_MINUTES = 1;
    // OC可接受延误阈值，超过该分钟数才提醒指挥官
    private static final int OC_ACCEPTABLE_DELAY_MINUTES = 5;
    // 延误提醒中只展示到分钟的时间格式
    private static final DateTimeFormatter OC_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public void init() {
        List<Long> noticeFactionIdList = new ArrayList<>();
        noticeFactionIdList.add(TornConstants.FACTION_PN_ID);
        noticeFactionIdList.add(TornConstants.FACTION_SH_ID);
        noticeFactionIdList.add(TornConstants.FACTION_HP_ID);
        noticeFactionIdList.add(TornConstants.FACTION_BSU_ID);
        noticeFactionIdList.add(TornConstants.FACTION_PTA_ID);
        noticeFactionIdList.add(TornConstants.FACTION_CCRC_ID);

        for (long factionId : noticeFactionIdList) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
            if (faction.getGroupId().equals(0L)) {
                continue;
            }
            scheduleOcTask(faction);
            scheduleOcCompleteCheck(faction);
        }
    }

    /**
     * 定时更新OC任务
     */
    private void scheduleOcTask(TornSettingFactionDO faction) {
        List<TornFactionOcDO> planningList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, faction.getId())
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                .eq(TornFactionOcDO::getHasNoticed, false)
                .orderByAsc(TornFactionOcDO::getReadyTime)
                .list();

        if (CollectionUtils.isEmpty(planningList)) {
            taskService.updateTask(faction.getFactionShortName() + "-oc-complete",
                    () -> noticeCompleteUsers(faction, List.of()), LocalDateTime.now().plusHours(1));
        } else {
            TornFactionOcDO firstOc = planningList.getFirst();
            LocalDateTime noticeTime = firstOc.getReadyTime().minusMinutes(3);

            LocalDateTime windowEnd = firstOc.getReadyTime().plusMinutes(TIME_WINDOW_MINUTES);
            List<TornFactionOcDO> ocList = planningList.stream()
                    .filter(oc -> !oc.getReadyTime().isAfter(windowEnd))
                    .toList();
            taskService.updateTask(faction.getFactionShortName() + "-oc-complete",
                    () -> noticeCompleteUsers(faction, ocList), noticeTime);
        }
    }

    /**
     * 批量通知完成的用户
     */
    private void noticeCompleteUsers(TornSettingFactionDO faction, List<TornFactionOcDO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            scheduleOcTask(faction);
            return;
        }

        // 1. 刷新现有数据, 查询所有即将释放的用户
        ocRefreshManager.refreshOc(1, faction.getId());
        List<TornFactionOcSlotDO> slotList = ocSlotDao.queryListByOc(ocList);

        // 2. 查询这些用户的成功率数据
        List<Long> userIdList = slotList.stream()
                .map(TornFactionOcSlotDO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 3. 用户映射
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdList);

        // 4. 查询API原始数据获取 item_requirement
        Map<Long, TornFactionCrimeSlotVO> slotMap = fetchSlotMap(faction.getId(), ocList);
        sendCommanderNotice(faction, ocList, slotMap, userIdList, userMap);

        // 5. 标记已通知 & 调度下一批
        Set<Long> ocIdSet = ocList.stream().map(TornFactionOcDO::getId).collect(Collectors.toSet());
        ocDao.lambdaUpdate().set(TornFactionOcDO::getHasNoticed, true).in(TornFactionOcDO::getId, ocIdSet).update();
        scheduleOcTask(faction);

        // 6. 调度OC完成检测任务（合并当前所有已通知未完成OC，避免覆盖旧批次）
        scheduleOcCompleteCheck(faction);
    }

    /**
     * 从Torn API获取OC slot原始数据，构建 userId → slot VO 映射
     */
    private Map<Long, TornFactionCrimeSlotVO> fetchSlotMap(long factionId, List<TornFactionOcDO> ocList) {
        TornFactionOcVO resp = tornApi.sendRequest(factionId, new TornFactionOcDTO(1, false),
                TornFactionOcVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getCrimes())) {
            return Map.of();
        }

        Map<Long, TornFactionCrimeSlotVO> slotMap = new HashMap<>();
        Set<Long> ocIdSet = ocList.stream().map(TornFactionOcDO::getId).collect(Collectors.toSet());
        for (TornFactionCrimeVO crime : resp.getCrimes()) {
            if (!ocIdSet.contains(crime.getId())) {
                continue;
            }
            for (TornFactionCrimeSlotVO slot : crime.getSlots()) {
                if (slot.getUserId() != null) {
                    slotMap.put(slot.getUserId(), slot);
                }
            }
        }

        return slotMap;
    }

    /**
     * 发送指挥官消息（OC详情 + 道具/状态警告）
     */
    private void sendCommanderNotice(TornSettingFactionDO faction, List<TornFactionOcDO> ocList,
                                     Map<Long, TornFactionCrimeSlotVO> slotMap,
                                     List<Long> userIdList, Map<Long, TornUserDO> userMap) {
        List<QqMsgParam<?>> msgList = new ArrayList<>(buildAtMsg(faction.getOcCommanderIds()));

        String ocCountText = String.format("即将有%d个OC结束，请注意是否需要生成新的OC", ocList.size());
        msgList.add(new TextQqMsg("\n" + ocCountText + "\n\n"));
        msgList.add(ImageQqMsg.fromBase64(msgManager.buildOcTable(
                faction.getFactionShortName() + " OC即将结束", ocList)));

        List<QqMsgParam<?>> itemWarnings = buildItemWarnings(slotMap, userMap);
        List<QqMsgParam<?>> statusWarnings = buildStatusWarnings(faction.getId(), userIdList, userMap);
        if (!itemWarnings.isEmpty() || !statusWarnings.isEmpty()) {
            msgList.addAll(itemWarnings);
            msgList.addAll(statusWarnings);
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 构建道具缺失提醒（直接从API原始数据读取 item_requirement，不依赖DB）
     */
    private List<QqMsgParam<?>> buildItemWarnings(Map<Long, TornFactionCrimeSlotVO> slotMap,
                                                  Map<Long, TornUserDO> userMap) {
        List<QqMsgParam<?>> warnings = new ArrayList<>();
        for (Map.Entry<Long, TornFactionCrimeSlotVO> entry : slotMap.entrySet()) {
            Long userId = entry.getKey();
            TornFactionCrimeRequireItemVO itemReq = entry.getValue().getItemRequirement();
            if (itemReq == null || Boolean.TRUE.equals(itemReq.getIsAvailable())) {
                continue;
            }
            TornUserDO user = userMap.get(userId);
            if (user != null && !user.getQqId().equals(0L)) {
                warnings.add(new AtQqMsg(user.getQqId()));
            }

            String itemName = itemsManager.getMap().containsKey(itemReq.getId())
                    ? itemsManager.getMap().get(itemReq.getId()).getItemName()
                    : "#" + itemReq.getId();
            warnings.add(new TextQqMsg("OC需要道具: " + itemName + "，请购买\n"));
        }

        return warnings;
    }

    /**
     * 构建用户状态异常提醒（一次帮派成员接口批量取状态）
     *
     * @param factionId  帮派ID
     * @param userIdList 参与OC的用户ID列表
     * @param userMap    用户映射
     * @return 异常状态提醒消息列表
     */
    private List<QqMsgParam<?>> buildStatusWarnings(long factionId, List<Long> userIdList,
                                                    Map<Long, TornUserDO> userMap) {
        Map<Long, String> badStatusMap = findBadStatusMap(factionId, userIdList);
        List<QqMsgParam<?>> warnings = new ArrayList<>();
        for (Map.Entry<Long, String> entry : badStatusMap.entrySet()) {
            TornUserDO user = userMap.get(entry.getKey());
            if (user != null && !user.getQqId().equals(0L)) {
                warnings.add(new AtQqMsg(user.getQqId()));
            }
            warnings.add(new TextQqMsg(buildStatusTip(user, entry.getKey(), entry.getValue())));
        }
        return warnings;
    }

    /**
     * 查询帮派成员中状态异常（不可执行OC）的OC参与成员
     *
     * @param factionId  帮派ID
     * @param userIdList 参与OC的用户ID列表
     * @return Key为用户ID，Value为异常状态码
     */
    private Map<Long, String> findBadStatusMap(long factionId, List<Long> userIdList) {
        TornFactionMemberListVO resp = tornApi.sendRequest(
                new TornFactionMemberDTO(factionId), TornFactionMemberListVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
            return Map.of();
        }

        Set<Long> ocUserIdSet = new HashSet<>(userIdList);
        Map<Long, String> badStatusMap = new HashMap<>();
        for (TornFactionMemberVO member : resp.getMembers()) {
            if (ocUserIdSet.contains(member.getId()) && member.getStatus() != null
                    && TornUserStatusEnum.isOcNotExecutable(member.getStatus().getState())) {
                badStatusMap.put(member.getId(), member.getStatus().getState());
            }
        }
        return badStatusMap;
    }

    /**
     * 构建单个成员的状态异常提示文案
     *
     * @param user   用户信息，可为空
     * @param userId 用户ID
     * @param state  异常状态码
     * @return 提示文案
     */
    private String buildStatusTip(TornUserDO user, long userId, String state) {
        String name = user != null ? user.getNickname() : String.valueOf(userId);
        TornUserStatusEnum statusEnum = TornUserStatusEnum.codeOf(state);
        String tip = statusEnum == null ? "状态异常(" + state + ")，请处理" :
                switch (statusEnum) {
                    case TRAVELING -> "在旅行中，请尽快返回";
                    case ABROAD -> "滞留国外，请尽快返回";
                    case HOSPITAL -> "在住院中，请尽快出院";
                    case JAIL -> "在监狱中，请尽快出狱";
                    default -> "状态异常(" + state + ")，请处理";
                };
        return name + " " + tip + "\n";
    }

    /**
     * 调度OC完成检测任务
     * 在readyAt后下一个整分钟的第30秒首次检查，之后每分钟重试直到OC完成
     *
     * <p>每次基于当前所有已通知未完成 OC 统一调度，避免同帮派多个批次使用同一个任务 ID 时相互覆盖。</p>
     */
    private void scheduleOcCompleteCheck(TornSettingFactionDO faction) {
        List<TornFactionOcDO> ocList = ocDao.queryNoticedNotCompleteByFaction(faction.getId());
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<Long> ocIdList = ocList.stream().map(TornFactionOcDO::getId).toList();
        log.info("调度OC完成检测, factionId={}, ocIds={}", faction.getId(), ocIdList);

        // 取最早readyAt，计算首次检查时间 = readyAt之后的下一个整分钟:30
        LocalDateTime earliestReady = ocList.stream()
                .map(TornFactionOcDO::getReadyTime)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());

        // 下一个整分钟
        LocalDateTime nextMinute = earliestReady.withSecond(0).withNano(0);
        if (!nextMinute.isAfter(earliestReady)) {
            nextMinute = nextMinute.plusMinutes(1);
        }
        // 在下一个整分钟的第30秒检查（避免Torn执行秒数漂移问题）
        LocalDateTime firstCheckTime = nextMinute.plusSeconds(30);
        // readyTime 已经过期时，按当前时间退避，避免恢复或异常重试立即自旋
        if (!firstCheckTime.isAfter(LocalDateTime.now())) {
            firstCheckTime = LocalDateTime.now().plusMinutes(1);
        }

        String taskId = faction.getFactionShortName() + "-oc-complete-check";
        taskService.updateTask(taskId,
                () -> checkOcCompleted(faction, ocIdList),
                firstCheckTime);
    }

    /**
     * 检查OC是否已完成（轮询逻辑）
     */
    private void checkOcCompleted(TornSettingFactionDO faction, List<Long> ocIdList) {
        // 刷新OC数据
        try {
            ocRefreshManager.refreshOc(1, faction.getId());
        } catch (Exception e) {
            log.error("OC完成检测刷新异常，稍后重试, factionId={}, ocIds={}",
                    faction.getId(), ocIdList, e);
            scheduleOcCompleteCheck(faction);
            return;
        }

        // 查询这些OC的最新状态
        List<TornFactionOcDO> currentOcList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getId, ocIdList)
                .list();

        List<String> completeStatuses = TornOcStatusEnum.getCompleteStatusList();

        // 分离已完成和未完成的OC
        List<TornFactionOcDO> completedOcs = currentOcList.stream()
                .filter(oc -> completeStatuses.contains(oc.getStatus()))
                .toList();
        List<TornFactionOcDO> pendingOcs = currentOcList.stream()
                .filter(oc -> !completeStatuses.contains(oc.getStatus()))
                .toList();

        // 已完成的OC立即发送通知
        if (!completedOcs.isEmpty()) {
            List<Long> completedOcIds = completedOcs.stream().map(TornFactionOcDO::getId).toList();
            List<TornFactionOcSlotDO> completedSlots = ocSlotDao.lambdaQuery()
                    .in(TornFactionOcSlotDO::getOcId, completedOcIds).list();
            List<Long> completedUserIds = completedSlots.stream()
                    .map(TornFactionOcSlotDO::getUserId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            sendOcCompleteNotice(faction, completedUserIds, completedOcs);
        }

        // 未完成的继续轮询
        if (!pendingOcs.isEmpty()) {
            scheduleOcCompleteCheck(faction);
        }
    }

    /**
     * 发送OC完成通知（带推荐表格）
     */
    private void sendOcCompleteNotice(TornSettingFactionDO faction, List<Long> userIdList,
                                      List<TornFactionOcDO> ocList) {
        // 构建消息列表
        List<QqMsgParam<?>> msgList = new ArrayList<>();

        // @所有参与成员
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdList);
        for (long userId : userIdList) {
            TornUserDO user = userMap.get(userId);
            if (user != null && !user.getQqId().equals(0L)) {
                msgList.add(new AtQqMsg(user.getQqId()));
            } else {
                String name = user != null ? user.getNickname() : String.valueOf(userId);
                msgList.add(new TextQqMsg(name + "[" + userId + "] "));
            }
        }

        // OC完成文本
        StringBuilder ocDesc = new StringBuilder("\nOC ");
        List<String> ocNames = ocList.stream()
                .map(oc -> "#" + oc.getRank() + " " + oc.getName())
                .toList();
        ocDesc.append(String.join("、", ocNames));
        ocDesc.append(" 已完成，可以加入新的OC了\n\n");

        msgList.add(new TextQqMsg(ocDesc.toString()));

        // 推荐表格（仅对本次完成的OC成员做推荐）
        List<TornFactionOcUserDO> allUsers = ocUserDao.queryByUserId(userIdList);
        Map<Long, List<TornFactionOcUserDO>> completeUserMap = allUsers.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));
        Map<TornUserDO, List<TornFactionOcUserDO>> paramMap = new TreeMap<>(Comparator.comparing(TornUserDO::getId));
        completeUserMap.forEach((k, v) -> {
            if (userMap.containsKey(k)) {
                paramMap.put(userMap.get(k), v);
            }
        });
        Map<TornUserDO, OcRecommendationVO> recommendMap = assignService.assignUserList(faction.getId(), paramMap);
        if (CollectionUtils.isEmpty(recommendMap) || recommendMap.values().stream().noneMatch(Objects::nonNull)) {
            msgList.add(new TextQqMsg("暂未适合加入的OC，联系OC指挥官生成"));
        } else {
            msgList.add(new TextQqMsg("推荐按以下岗位加入：\n"));
            String title = faction.getFactionShortName() + " OC队伍分配建议";
            msgList.add(ImageQqMsg.fromBase64(msgManager.buildRecommendTable(title, faction.getId(), recommendMap)));
        }

        // 明显延误提醒：合并到当前完成通知，不单独发送消息，展示在推荐表格图片下方
        msgList.addAll(buildDelayNotice(faction, ocList));

        // 发送
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 计算OC计划完成时间。
     * Torn在准备时间所在分钟的下一分钟统一执行。
     *
     * @param readyTime OC准备时间
     * @return 计划完成时间
     */
    private LocalDateTime calculatePlannedTime(LocalDateTime readyTime) {
        return readyTime.truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(OC_PLANNED_START_OFFSET_MINUTES);
    }

    /**
     * 计算OC实际完成时间的展示口径，秒和纳秒归零。
     *
     * @param executedTime OC实际执行完成时间
     * @return 实际完成时间
     */
    private LocalDateTime calculateActualTime(LocalDateTime executedTime) {
        return executedTime.truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * 按分钟桶计算延误分钟数。
     *
     * @param oc OC数据
     * @return 延误分钟数；无法计算或时间异常时为空
     */
    private OptionalLong calculateDelayMinutes(TornFactionOcDO oc) {
        if (oc.getReadyTime() == null || oc.getExecutedTime() == null) {
            log.debug("OC完成时间缺失，跳过延误计算, ocId={}, readyTime={}, executedTime={}",
                    oc.getId(), oc.getReadyTime(), oc.getExecutedTime());
            return OptionalLong.empty();
        }
        LocalDateTime plannedTime = calculatePlannedTime(oc.getReadyTime());
        LocalDateTime actualTime = calculateActualTime(oc.getExecutedTime());
        long delayMinutes = ChronoUnit.MINUTES.between(plannedTime, actualTime);
        if (delayMinutes < 0) {
            log.warn("OC完成时间异常，实际完成早于计划完成，跳过延误提醒, ocId={}, readyTime={}, executedTime={}",
                    oc.getId(), oc.getReadyTime(), oc.getExecutedTime());
            return OptionalLong.empty();
        }
        return OptionalLong.of(delayMinutes);
    }

    /**
     * 构建明显延误提醒消息段；没有明显延误时返回空列表。
     *
     * @param faction 帮派配置
     * @param ocList  本次完成的OC列表
     * @return 延误提醒消息参数列表
     */
    private List<QqMsgParam<?>> buildDelayNotice(TornSettingFactionDO faction, List<TornFactionOcDO> ocList) {
        List<OcDelayInfo> delayedOcs = new ArrayList<>();
        Set<Long> seenOcIds = new HashSet<>();
        for (TornFactionOcDO oc : ocList) {
            if (oc.getId() == null || seenOcIds.add(oc.getId())) {
                OptionalLong delayMinutes = calculateDelayMinutes(oc);
                if (delayMinutes.isPresent() && delayMinutes.getAsLong() > OC_ACCEPTABLE_DELAY_MINUTES) {
                    delayedOcs.add(new OcDelayInfo(oc, calculatePlannedTime(oc.getReadyTime()),
                            calculateActualTime(oc.getExecutedTime()), delayMinutes.getAsLong()));
                }
            }
        }
        if (delayedOcs.isEmpty()) {
            return List.of();
        }

        List<QqMsgParam<?>> delayMsgs = new ArrayList<>(buildAtMsg(faction.getOcCommanderIds()));
        List<String> detailLines = delayedOcs.stream()
                .map(this::buildDelayDetail)
                .toList();
        delayMsgs.add(new TextQqMsg("\n以下OC完成时存在明显延误，请关注：\n\n"
                + String.join("\n", detailLines) + "\n"));
        return delayMsgs;
    }

    /**
     * 构建单个OC的延误明细文案。
     *
     * @param delayInfo 延误计算中间结果
     * @return 延误明细文案
     */
    private String buildDelayDetail(OcDelayInfo delayInfo) {
        String name = "#" + delayInfo.oc().getRank() + " " + delayInfo.oc().getName();
        return name + "：计划" + delayInfo.plannedTime().format(OC_TIME_FORMATTER)
                + "完成，实际" + delayInfo.actualTime().format(OC_TIME_FORMATTER)
                + "完成，延误约" + delayInfo.delayMinutes() + "分钟";
    }

    /**
     * 单个OC的延误计算中间结果。
     *
     * @param oc           OC数据
     * @param plannedTime  计划完成时间
     * @param actualTime   实际完成时间（分钟口径）
     * @param delayMinutes 延误分钟数
     */
    private record OcDelayInfo(TornFactionOcDO oc, LocalDateTime plannedTime,
                               LocalDateTime actualTime, long delayMinutes) {
    }

    /**
     * 构建At消息
     */
    private List<? extends QqMsgParam<?>> buildAtMsg(String userIdString) {
        if (userIdString == null || userIdString.isBlank()) {
            return List.of();
        }
        return Arrays.stream(userIdString.split(","))
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .map(this::parseCommanderId)
                .filter(Objects::nonNull)
                .map(AtQqMsg::new)
                .toList();
    }

    /**
     * 解析指挥官QQ号；配置无效时记录告警并忽略，避免阻塞完成通知。
     *
     * @param commanderId 指挥官QQ号文本
     * @return 解析后的QQ号，无效时返回null
     */
    private Long parseCommanderId(String commanderId) {
        try {
            return Long.parseLong(commanderId);
        } catch (NumberFormatException e) {
            log.warn("OC指挥官QQ配置无效，已忽略该配置");
            return null;
        }
    }
}
