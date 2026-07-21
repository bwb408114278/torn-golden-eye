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
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 禁止OC加入提醒逻辑层。
 * <p>定时检测是否有成员加入了帮派禁用的OC，如有则在帮派群内合并发送提醒消息。</p>
 *
 * @author Bai
 * @version 1.2.11
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
    private final TornUserDAO userDao;
    private final TornSettingFactionManager settingFactionManager;
    private final TornSettingFactionOcManager settingFactionOcManager;

    /**
     * 扫描所有帮派，检测并提醒加入了禁止OC的成员。
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

        List<TornFactionOcDO> bannedOcList = activeOcList.stream()
                .filter(oc -> settingFactionOcManager.isOcDisabled(faction.getId(), oc))
                .toList();
        if (bannedOcList.isEmpty()) {
            return;
        }

        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(bannedOcList);
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
        sendBannedNotice(faction, bannedOcList, occupiedSlots, userMap);
    }

    /**
     * 构建并发送禁止OC加入提醒消息。
     *
     * @param faction       帮派设置
     * @param bannedOcList  禁止的OC列表
     * @param occupiedSlots 有人的OC岗位列表
     * @param userMap       用户映射
     */
    private void sendBannedNotice(TornSettingFactionDO faction, List<TornFactionOcDO> bannedOcList,
                                  List<TornFactionOcSlotDO> occupiedSlots, Map<Long, TornUserDO> userMap) {
        Map<Long, String> ocNameByOcId = bannedOcList.stream()
                .collect(Collectors.toMap(TornFactionOcDO::getId, TornFactionOcDO::getName));

        List<QqMsgParam<?>> msgList = new ArrayList<>();

        // @OC指挥官
        List<Long> commanderIds = NumberUtils.splitToLongList(faction.getOcCommanderIds());
        for (Long qqId : commanderIds) {
            msgList.add(new AtQqMsg(qqId));
        }

        msgList.add(new TextQqMsg("\n帮派有人偷偷加入了禁用OC，快来管管！\n\n"));

        // 按用户聚合，同一用户只@一次
        Set<Long> noticedUserIdSet = new HashSet<>();
        for (TornFactionOcSlotDO slot : occupiedSlots) {
            Long userId = slot.getUserId();
            TornUserDO user = userMap.get(userId);
            String ocName = ocNameByOcId.get(slot.getOcId());
            String displayName = user != null ? user.getNickname() : String.valueOf(userId);

            // 同一用户只@一次，后续行仅展示文本
            if (!noticedUserIdSet.contains(userId)) {
                if (user != null && !user.getQqId().equals(0L)) {
                    msgList.add(new AtQqMsg(user.getQqId()));
                } else {
                    msgList.add(new TextQqMsg(displayName + " "));
                }
                noticedUserIdSet.add(userId);
            }

            msgList.add(new TextQqMsg(" 赶紧醒一醒，你加入了禁用的OC：" + ocName + "\n"));
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }
}
