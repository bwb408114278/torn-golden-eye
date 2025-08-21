package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcUserManager;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OC完成逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornFactionOcValidService extends BaseTornFactionOcNoticeService {
    private final Bot bot;
    private final DynamicTaskService taskService;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornFactionOcUserManager ocUserManager;
    private final ResourceLoader resourceLoader;

    /**
     * 构建提醒
     */
    public Runnable buildNotice(TornFactionOcNoticeBO param) {
        return new Notice(param);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * OC ID
         */
        private final TornFactionOcNoticeBO param;

        @Override
        public void run() {
            param.refreshOc().run();

            List<TornFactionOcDO> recList = findRecList(param);
//            if (LocalDateTime.now().isBefore(planOc.getReadyTime())) {
//                checkFalseStart(planOc, recList);
//                taskService.updateTask(TornConstants.TASK_ID_OC_VALID + planOc.getRank(),
//                        buildNotice(planId, refreshOc, reloadSchedule),
//                        DateTimeUtils.convertToInstant(LocalDateTime.now().plusSeconds(10L)));
//            } else {
            checkPositionFull(recList);
//            }
        }

        /**
         * 检查抢跑
         */
        private void checkFalseStart(TornFactionOcDO planOc, List<TornFactionOcDO> recList) {
            List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(recList);

            List<TornFactionOcSlotDO> falseStartList = new ArrayList<>();
            for (TornFactionOcSlotDO slot : slotList) {
                boolean isJoinBeforePlan = slot.getJoinTime() != null &&
                        slot.getJoinTime().toLocalDate().equals(LocalDate.now()) &&
                        slot.getJoinTime().isBefore(planOc.getReadyTime());
                if (isJoinBeforePlan) {
                    falseStartList.add(slot);
                }
            }

            if (!CollectionUtils.isEmpty(falseStartList)) {
                GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                        .setGroupId(BotConstants.PN_GROUP_ID)
                        .addMsg(new TextQqMsg("抢跑啦! 踢掉词条要添新素材啦, 加入时间为 + " +
                                DateTimeUtils.convertToString(planOc.getReadyTime()) + "\n"));
                List<QqMsgParam<?>> paramList = new ArrayList<>();
                for (int i = 0; i < falseStartList.size(); i++) {
                    TornFactionOcSlotDO slot = falseStartList.get(i);
                    paramList.addAll(msgManager.buildSlotMsg(List.of(slot), null));
                    paramList.add(new TextQqMsg(String.format(" 加入时间为 %s%s",
                            DateTimeUtils.convertToString(slot.getJoinTime()),
                            i + 1 == falseStartList.size() ? "" : "\n")));
                }

                bot.sendRequest(builder.addMsg(paramList).build(), String.class);
            }
        }

        /**
         * 检查车位已满
         */
        private void checkPositionFull(List<TornFactionOcDO> recList) {
            boolean isLackNew = recList.size() < 6;

            Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap = buildLackMap(recList);
            if (lackMap.isEmpty() && !isLackNew) {
                Resource resource = resourceLoader.getResource("classpath:/img/ocSlotFull.gif");
                try (InputStream inputStream = resource.getInputStream()) {
                    byte[] imageBytes = inputStream.readAllBytes();
                    BotHttpReqParam botParam = new GroupMsgHttpBuilder()
                            .setGroupId(BotConstants.PN_GROUP_ID)
                            .addMsg(new ImageQqMsg(Base64.getEncoder().encodeToString(imageBytes)))
                            .build();
                    bot.sendRequest(botParam, String.class);
                } catch (IOException e) {
                    throw new BizException("发送车位已满消息出错", e);
                }
                // 车位已满，重载任务时间
                ocManager.refreshRotationSetting(param.planKey(), param.excludePlanKey(),
                        param.recKey(), param.excludeRecKey(), param.rank());
                param.reloadSchedule().run();
            } else {
                sendLackMsg(isLackNew, lackMap);
            }
        }

        /**
         * 发送OC队伍缺人的消息
         */
        private void sendLackMsg(boolean isLackNew, Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap) {
            Set<Long> userIdSet = ocUserManager.findRotationUser(param.rank());
            int lackCount = param.lackCount();
            int freeCount = param.freeCount();
            if (param.lackCount() == 0 || lackMap.size() < param.lackCount() || userIdSet.size() != param.freeCount()) {
                lackCount = lackMap.size();
                freeCount = userIdSet.size();

                String noticeMsg = isLackNew ?
                        "还剩" + (lackMap.size() + 1) + "坑, 包含新队一坑\n" :
                        "还剩" + lackMap.size() + "坑\n";
                GroupMsgHttpBuilder msgBuilder = new GroupMsgHttpBuilder()
                        .setGroupId(BotConstants.PN_GROUP_ID)
                        .addMsg(new TextQqMsg(noticeMsg))
                        .addMsg(msgManager.buildAtMsg(userIdSet));

                if (!lackMap.isEmpty()) {
                    String title = buildRankDesc(param) + (isLackNew ? "级OC缺人队伍（未包含新队）" : "级OC缺人队伍");
                    TableDataBO table = msgTableManager.buildOcTable(title, lackMap);
                    msgBuilder.addMsg(new ImageQqMsg(TableImageUtils.renderTableToBase64(table)));
                }

                bot.sendRequest(msgBuilder.build(), String.class);
            }

            taskService.updateTask(param.taskId(),
                    new Notice(new TornFactionOcNoticeBO(param.planId(), param.taskId(),
                            param.planKey(), param.excludePlanKey(), param.recKey(), param.excludeRecKey(),
                            param.refreshOc(), param.reloadSchedule(), lackCount, freeCount, param.rank())),
                    LocalDateTime.now().plusMinutes(1L));
        }
    }
}