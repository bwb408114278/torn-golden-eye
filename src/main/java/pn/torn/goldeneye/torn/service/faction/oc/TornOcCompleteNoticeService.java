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

    public void init() {
        for (long factionId : TornConstants.REASSIGN_OC_FACTION) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
            if (!faction.getId().equals(TornConstants.FACTION_PN_ID) || faction.getGroupId().equals(0L)) {
                continue;
            }

            scheduleOcTask(faction, 0L);
        }
    }

    /**
     * 定时更新OC任务
     */
    private void scheduleOcTask(TornSettingFactionDO faction, long execOcId) {
        List<TornFactionOcDO> planningList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, faction.getId())
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                .ne(execOcId != 0L, TornFactionOcDO::getId, execOcId)
                .orderByAsc(TornFactionOcDO::getReadyTime)
                .list();
        if (CollectionUtils.isEmpty(planningList)) {
            taskService.updateTask(faction.getFactionShortName() + "-oc-complete",
                    () -> noticeCompleteUser(faction, 0L), LocalDateTime.now().plusHours(1));
        } else {
            TornFactionOcDO oc = planningList.getFirst();
            taskService.updateTask(faction.getFactionShortName() + "-oc-complete",
                    () -> noticeCompleteUser(faction, oc.getId()), oc.getReadyTime().minusMinutes(3));
        }
    }

    /**
     * 通知完成的用户
     */
    private void noticeCompleteUser(TornSettingFactionDO faction, long ocId) {
        if (ocId == 0L) {
            scheduleOcTask(faction, 0L);
            return;
        }

        // 1. 刷新现有数据, 查询OC马上要释放的用户
        ocRefreshManager.refreshOc(1, faction.getId());
        List<TornFactionOcSlotDO> slotList = ocSlotDao.queryListByOc(ocId);

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
        msgList.add(new TextQqMsg("\n即将有OC结束, 请注意是否需要生成新的OC\n\n"));
        msgList.addAll(msgManager.buildAtMsg(userIdList, faction.getGroupId()));
        msgList.add(new TextQqMsg("\nOC还有3分钟结束, 推荐按以下岗位加入\n"));

        String title = faction.getFactionShortName() + " OC队伍分配建议";
        msgList.add(new ImageQqMsg(msgManager.buildRecommendTable(title, faction.getId(), recommendMap)));

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
        scheduleOcTask(faction, ocId);
    }

    /**
     * 构建At消息
     */
    private List<? extends QqMsgParam<?>> buildAtMsg(String userIdString) {
        String[] commanders = userIdString.split(",");
        return Arrays.stream(commanders).map(s -> new AtQqMsg(Long.parseLong(s))).toList();
    }
}