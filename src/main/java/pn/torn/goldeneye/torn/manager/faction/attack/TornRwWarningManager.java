package pn.torn.goldeneye.torn.manager.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.user.TornPlaneTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornTravelStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornTravelTargetEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.AtQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwUserStatusDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwUserStatusDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 帮派RW警告公共逻辑类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornRwWarningManager {
    private final Bot bot;
    private final TornUserManager userManager;
    private final TornSettingFactionManager factionManager;
    private final TornFactionRwUserStatusDAO userStatusDao;

    /**
     * 发送警告
     */
    public void sendWarning(TornFactionRwDO rw, LocalDateTime dateTime, Collection<TornFactionMemberVO> memberList) {
        sendOnlineWarning(rw, memberList);
        checkTravelingWarning(rw, dateTime, memberList);
    }

    /**
     * 发送在线提醒
     */
    private void sendOnlineWarning(TornFactionRwDO rw, Collection<TornFactionMemberVO> memberList) {
        if (CollectionUtils.isEmpty(memberList)) {
            return;
        }

        int factionOnlineCount = 0;
        int opponentOnlineCount = 0;
        int opponentOkayCount = 0;
        for (TornFactionMemberVO member : memberList) {
            if (TornConstants.USER_STATUS_OFFLINE.equals(member.getLastAction().getStatus()) ||
                    TornUserStatusEnum.TRAVELING.getCode().equals(member.getStatus().getState()) ||
                    TornUserStatusEnum.ABROAD.getCode().equals(member.getStatus().getState())) {
                continue;
            }

            TornUserDO user = userManager.getUserById(member.getId());
            if (user != null && user.getFactionId().equals(rw.getFactionId())) {
                factionOnlineCount++;
            } else {
                opponentOnlineCount++;
                if (TornUserStatusEnum.OKAY.getCode().equals(member.getStatus().getState())) {
                    opponentOkayCount++;
                }
            }
        }

        log.info("RW在线检测: 时间: {}, 我方绿灯{}人, 对方{}人",
                DateTimeUtils.convertToString(LocalDateTime.now()), factionOnlineCount, opponentOnlineCount);
        if (opponentOnlineCount > factionOnlineCount && opponentOkayCount >= 10) {
            TornSettingFactionDO faction = factionManager.getIdMap().get(rw.getFactionId());
            List<QqMsgParam<?>> msgList = new ArrayList<>(buildCommanderAtMsgList(faction));
            msgList.add(new TextQqMsg("\n注意! 检测到RW对方绿灯" + opponentOnlineCount + "人, 我方仅" + factionOnlineCount + "人"));
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(faction.getGroupId())
                    .addMsg(msgList)
                    .build();
            bot.sendRequest(param, String.class);
        }
    }

    /**
     * 发送起飞提醒
     */
    private void checkTravelingWarning(TornFactionRwDO rw, LocalDateTime dateTime,
                                       Collection<TornFactionMemberVO> memberList) {
        if (CollectionUtils.isEmpty(memberList)) {
            return;
        }

        List<TornFactionRwUserStatusDO> factionMemberList = new ArrayList<>();
        List<TornFactionRwUserStatusDO> newDataList = new ArrayList<>();
        List<TornFactionRwUserStatusDO> updateDataList = new ArrayList<>();
        fillDataList(rw, memberList, factionMemberList, newDataList, updateDataList);

        if (!CollectionUtils.isEmpty(newDataList)) {
            userStatusDao.saveBatch(newDataList);
        }

        if (!CollectionUtils.isEmpty(updateDataList)) {
            userStatusDao.updateBatchById(updateDataList);
            sendTravelingWarningMsg(rw.getFactionId(), dateTime, factionMemberList, updateDataList);
        }
    }

    /**
     * 发送海外警告消息
     */
    private void sendTravelingWarningMsg(long factionId, LocalDateTime dateTime,
                                         List<TornFactionRwUserStatusDO> memberList,
                                         List<TornFactionRwUserStatusDO> opponentList) {
        TornSettingFactionDO faction = factionManager.getIdMap().get(factionId);
        List<QqMsgParam<?>> msgList = new ArrayList<>();
        for (TornTravelTargetEnum target : TornTravelTargetEnum.values()) {
            List<TornFactionRwUserStatusDO> targetList = opponentList.stream()
                    .filter(o -> o.getTravelTarget().equals(target.getCode())).toList();
            if (targetList.isEmpty()) {
                continue;
            }

            msgList.add(new TextQqMsg(buildTravelOpponentMsg(dateTime, target, targetList)));
            msgList.addAll(buildTravelMemberMsg(memberList, opponentList, target));
            msgList.add(new TextQqMsg("\n\n"));
        }

        msgList.removeLast();
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 填充数据列表
     */
    private void fillDataList(TornFactionRwDO rw, Collection<TornFactionMemberVO> memberList,
                              List<TornFactionRwUserStatusDO> factionMemberList,
                              List<TornFactionRwUserStatusDO> newDataList,
                              List<TornFactionRwUserStatusDO> updateDataList) {
        List<TornFactionRwUserStatusDO> statusList = userStatusDao.lambdaQuery()
                .eq(TornFactionRwUserStatusDO::getRwId, rw.getId())
                .list();
        for (TornFactionMemberVO member : memberList) {
            boolean isTravel = TornUserStatusEnum.TRAVELING.getCode().equals(member.getStatus().getState()) ||
                    TornUserStatusEnum.ABROAD.getCode().equals(member.getStatus().getState());
            TornUserDO user = userManager.getUserById(member.getId());
            if (user != null && user.getFactionId().equals(rw.getFactionId())) {
                if (isTravel) {
                    factionMemberList.add(new TornFactionRwUserStatusDO(rw.getFactionId(), rw.getId(), member));
                }
            } else {
                TornFactionRwUserStatusDO status = statusList.stream()
                        .filter(s -> s.getUserId().equals(member.getId()))
                        .findAny().orElse(null);
                if (status == null) {
                    newDataList.add(new TornFactionRwUserStatusDO(rw.getFactionId(), rw.getId(), member));
                } else if (!member.getStatus().getState().equals(status.getState()) && isTravel) {
                    updateDataList.add(new TornFactionRwUserStatusDO(status.getId(), member));
                }
            }
        }
    }

    /**
     * 构建海外对手信息
     */
    private String buildTravelOpponentMsg(LocalDateTime dateTime, TornTravelTargetEnum target,
                                          List<TornFactionRwUserStatusDO> targetList) {
        StringBuilder msgBuilder = new StringBuilder();
        msgBuilder.append("RW检测到对方海外变动, 目的地: ").append(target.getName());
        for (TornFactionRwUserStatusDO status : targetList) {
            msgBuilder.append("\n").append(status.getNickname()).append(" [").append(status.getUserId()).append("] ");
            TornTravelStatusEnum travelStatus = TornTravelStatusEnum.codeOf(status.getTravelType());
            msgBuilder.append(travelStatus.getName());

            if (!TornTravelStatusEnum.IN.equals(travelStatus)) {
                TornPlaneTypeEnum planeType = TornPlaneTypeEnum.codeOf(status.getPlaneType());
                long minutes;
                switch (planeType) {
                    case PRIVATE -> minutes = target.getPrivateMinutes();
                    case BUSINESS -> minutes = target.getBusinessMinutes();
                    default -> minutes = target.getAirstripMinutes();
                }

                msgBuilder.append("(").append(planeType.getName())
                        .append("), 预计到达时间: ").append(DateTimeUtils.convertToString(dateTime.plusMinutes(minutes)));
            }
        }

        return msgBuilder.toString();
    }

    /**
     * 构建海外己方信息
     */
    private List<QqMsgParam<?>> buildTravelMemberMsg(List<TornFactionRwUserStatusDO> memberList,
                                                     List<TornFactionRwUserStatusDO> opponentList,
                                                     TornTravelTargetEnum target) {
        List<QqMsgParam<?>> msgList = new ArrayList<>();
        if (opponentList.stream()
                .filter(s -> target.getCode().equals(s.getTravelTarget()))
                .allMatch(s -> TornTravelStatusEnum.RETURNING.getCode().equals(s.getTravelType()))) {
            return msgList;
        }

        List<TornFactionRwUserStatusDO> travelMemberList = memberList.stream()
                .filter(o -> o.getTravelTarget().equals(target.getCode())).toList();
        if (!travelMemberList.isEmpty()) {
            msgList.add(new TextQqMsg("\n请注意以下人员海外人身安全: \n"));
            for (TornFactionRwUserStatusDO member : travelMemberList) {
                TornUserDO user = userManager.getUserById(member.getUserId());
                if (user.getQqId() > 0) {
                    msgList.add(new AtQqMsg(user.getQqId()));
                } else {
                    msgList.add(new TextQqMsg(user.getNickname() + "[" + user.getId() + "]"));
                }
            }
        }

        return msgList;
    }

    /**
     * 构建at指挥官的消息
     */
    private List<AtQqMsg> buildCommanderAtMsgList(TornSettingFactionDO faction) {
        return new ArrayList<>(Arrays.stream(faction.getWarCommanderIds()
                .split(",")).map(s -> new AtQqMsg(Long.parseLong(s))).toList());
    }
}