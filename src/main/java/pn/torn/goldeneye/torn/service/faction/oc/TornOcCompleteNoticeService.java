package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.AtQqMsg;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
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
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcAssignService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC完成通知逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.26
 */
@Service
@RequiredArgsConstructor
public class TornOcCompleteNoticeService {
    private final Bot bot;
    private final DynamicTaskService taskService;
    private final TornOcAssignService assignService;
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornFactionOcMsgManager msgManager;
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;
    private final TornUserDAO userDao;
    // 时间窗口: 3分钟内完成的OC合并通知
    private static final int TIME_WINDOW_MINUTES = 3;

    public void init() {
        for (long factionId : TornConstants.REASSIGN_OC_FACTION) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
            if (!faction.getId().equals(TornConstants.FACTION_PN_ID) || faction.getGroupId().equals(0L)) {
                continue;
            }
            scheduleOcTask(faction);
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
     *
     * @param ocList 即将完成的OC列表（时间窗口内的所有OC）
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
        List<Long> userIdList = slotList.stream().map(TornFactionOcSlotDO::getUserId).toList();
        List<TornFactionOcUserDO> allUsers = ocUserDao.queryByUserId(userIdList);
        Map<Long, List<TornFactionOcUserDO>> completeUserMap = allUsers.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));

        // 3. 组装参数
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdList);
        Map<TornUserDO, List<TornFactionOcUserDO>> paramMap = new TreeMap<>(Comparator.comparing(TornUserDO::getId));
        completeUserMap.forEach((k, v) -> paramMap.put(userMap.get(k), v));

        // 4. 获取推荐结果并发送消息
        Map<TornUserDO, OcRecommendationVO> recommendMap = assignService.assignUserList(faction.getId(), paramMap);
        List<QqMsgParam<?>> msgList = new ArrayList<>(buildAtMsg(faction.getOcCommanderIds()));

        String ocCountText = String.format("即将有%d个OC结束", ocList.size());
        msgList.add(new TextQqMsg("\n" + ocCountText + ", 请注意是否需要生成新的OC\n\n"));
        msgList.addAll(msgManager.buildAtMsg(userIdList, faction.getGroupId()));

        if (CollectionUtils.isEmpty(recommendMap) || recommendMap.values().stream().noneMatch(Objects::nonNull)) {
            msgList.add(new TextQqMsg("暂未适合加入的OC, 联系OC指挥官生成"));
        } else {
            msgList.add(new TextQqMsg("\nOC还有3分钟结束, 推荐按以下岗位加入\n没有及时加入记得用g#OC推荐~\n"));
            String title = faction.getFactionShortName() + " OC队伍分配建议";
            msgList.add(new ImageQqMsg(msgManager.buildRecommendTable(title, faction.getId(), recommendMap)));
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);

        // 4. 调度下一批任务, 排除已处理的OC
        Set<Long> ocIdSet = ocList.stream().map(TornFactionOcDO::getId).collect(Collectors.toSet());
        ocDao.lambdaUpdate().set(TornFactionOcDO::getHasNoticed, true).in(TornFactionOcDO::getId, ocIdSet).update();
        scheduleOcTask(faction);
    }

    /**
     * 构建At消息
     */
    private List<? extends QqMsgParam<?>> buildAtMsg(String userIdString) {
        String[] commanders = userIdString.split(",");
        return Arrays.stream(commanders).map(s -> new AtQqMsg(Long.parseLong(s))).toList();
    }
}