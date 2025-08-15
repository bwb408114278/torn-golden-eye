package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.TestProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.ImageGroupMsg;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcUserManager;
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
public class TornFactionOcValidService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final Bot bot;
    private final DynamicTaskService taskService;
    private final TornFactionOcManager ocManager;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcUserManager ocUserManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final ResourceLoader resourceLoader;
    private final TestProperty testProperty;

    /**
     * 构建提醒
     */
    public Runnable buildNotice(long planId, Runnable refreshOc, Runnable reloadSchedule, int lackCount, int freeCount) {
        return new Notice(planId, refreshOc, reloadSchedule, lackCount, freeCount);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * OC ID
         */
        private long planId;
        /**
         * 刷新OC的方式
         */
        private final Runnable refreshOc;
        /**
         * 重载定时任务的方式
         */
        private final Runnable reloadSchedule;
        /**
         * 确认队伍的数量
         */
        private int lackCount;
        /**
         * 空闲人数
         */
        private int freeCount;

        @Override
        public void run() {
            refreshOc.run();
            TornFactionOcDO planOc = ocDao.getById(this.planId);
            List<TornFactionOcDO> recList = ocManager.queryRotationRecruitList(planOc);
//            if (LocalDateTime.now().isBefore(planOc.getReadyTime())) {
//                checkFalseStart(planOc, recList);
//                taskService.updateTask(TornConstants.TASK_ID_OC_VALID + planOc.getRank(),
//                        buildNotice(planId, refreshOc, reloadSchedule),
//                        DateTimeUtils.convertToInstant(LocalDateTime.now().plusSeconds(10L)));
//            } else {
            checkPositionFull(planOc, recList);
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
                        .setGroupId(testProperty.getGroupId())
                        .addMsg(new TextGroupMsg("抢跑啦! 踢掉词条要添新素材啦, 加入时间为 + " +
                                DateTimeUtils.convertToString(planOc.getReadyTime()) + "\n"));
                List<GroupMsgParam<?>> paramList = new ArrayList<>();
                for (int i = 0; i < falseStartList.size(); i++) {
                    TornFactionOcSlotDO slot = falseStartList.get(i);
                    paramList.addAll(msgManager.buildSlotMsg(List.of(slot), null));
                    paramList.add(new TextGroupMsg(String.format(" 加入时间为 %s%s",
                            DateTimeUtils.convertToString(slot.getJoinTime()),
                            i + 1 == falseStartList.size() ? "" : "\n")));
                }

                bot.sendRequest(builder.addMsg(paramList).build(), String.class);
            }
        }

        /**
         * 检查车位已满
         */
        private void checkPositionFull(TornFactionOcDO planOc, List<TornFactionOcDO> recList) {
            Map<Long, List<TornFactionOcSlotDO>> slotMap = slotDao.queryMapByOc(recList);
            boolean isLackNew = recList.size() < 6;

            Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap = HashMap.newHashMap(recList.size());
            for (TornFactionOcDO oc : recList) {
                if (!oc.getReadyTime().toLocalDate().isAfter(LocalDate.now())) {
                    lackMap.put(oc, slotMap.get(oc.getId()));
                }
            }

            if (lackMap.isEmpty()) {
                Resource resource = resourceLoader.getResource("classpath:/img/ocSlotFull.gif");
                try (InputStream inputStream = resource.getInputStream()) {
                    byte[] imageBytes = inputStream.readAllBytes();
                    BotHttpReqParam param = new GroupMsgHttpBuilder()
                            .setGroupId(testProperty.getGroupId())
                            .addMsg(new ImageGroupMsg(Base64.getEncoder().encodeToString(imageBytes)))
                            .build();
                    bot.sendRequest(param, String.class);
                } catch (IOException e) {
                    throw new BizException("发送车位已满消息出错", e);
                }
                // 车位已满，重载任务时间
                virtualThreadExecutor.execute(() -> {
                    try {
                        Thread.sleep(1000L);
                        reloadSchedule.run();
                    } catch (InterruptedException e) {
                        log.error("车位已满时重载任务等待任务移除出错", e);
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                sendLackMsg(planOc, isLackNew, lackMap);
            }
        }

        /**
         * 发送OC队伍缺人的消息
         */
        private void sendLackMsg(TornFactionOcDO planOc, boolean isLackNew,
                                 Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap) {
            Set<Long> userIdSet = ocUserManager.findRotationUser(planOc.getRank());
            String title = isLackNew ? planOc.getRank() + "级OC缺人队伍（未包含新队）" : planOc.getRank() + "级OC缺人队伍";
            TableDataBO table = msgManager.buildOcTable(title, lackMap);
            String ocTableImage = TableImageUtils.renderTableToBase64(table);
            String noticeMsg = isLackNew ?
                    "还剩" + (lackMap.size() + 1) + "坑, 包含新队一坑\n" :
                    "还剩" + lackMap.size() + "坑\n";

            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(testProperty.getGroupId())
                    .addMsg(new TextGroupMsg(noticeMsg))
                    .addMsg(new ImageGroupMsg(ocTableImage))
                    .addMsg(msgManager.buildAtMsg(userIdSet))
                    .build();
            if (lackCount == 0 || lackMap.size() < lackCount || userIdSet.size() != freeCount) {
                lackCount = lackMap.size();
                freeCount = userIdSet.size();
                bot.sendRequest(param, String.class);
            }

            taskService.updateTask(TornConstants.TASK_ID_OC_VALID + planOc.getRank(),
                    buildNotice(planId, refreshOc, reloadSchedule, lackCount, freeCount),
                    DateTimeUtils.convertToInstant(LocalDateTime.now().plusMinutes(1L)));
        }
    }
}