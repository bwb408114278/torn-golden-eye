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
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcUserManager;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcValidManager;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OC校验逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornFactionOcValidService extends BaseTornFactionOcNoticeService {
    private final Bot bot;
    private final DynamicTaskService taskService;
    private final TornFactionOcValidManager validManager;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornFactionOcUserManager ocUserManager;
    private final ResourceLoader resourceLoader;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

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
                        .setGroupId(projectProperty.getGroupId())
                        .addMsg(new TextQqMsg("抢跑啦! 踢掉词条要添新素材啦, 加入时间为 + " +
                                DateTimeUtils.convertToString(planOc.getReadyTime()) + "\n"));
                List<QqMsgParam<?>> paramList = new ArrayList<>();
                for (int i = 0; i < falseStartList.size(); i++) {
                    TornFactionOcSlotDO slot = falseStartList.get(i);
                    paramList.addAll(msgManager.buildSlotMsg(List.of(slot)));
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
            boolean isNewOcCorrect = checkNewOc(recList);
            boolean noMoreMember = validManager.validMoreMember(recList);

            Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap = buildLackMap(recList);
            if (lackMap.isEmpty() && isNewOcCorrect && noMoreMember) {
                Resource resource = resourceLoader.getResource("classpath:/img/ocSlotFull.gif");
                try (InputStream inputStream = resource.getInputStream()) {
                    byte[] imageBytes = inputStream.readAllBytes();
                    BotHttpReqParam botParam = new GroupMsgHttpBuilder()
                            .setGroupId(projectProperty.getGroupId())
                            .addMsg(new ImageQqMsg(Base64.getEncoder().encodeToString(imageBytes)))
                            .build();
                    bot.sendRequest(botParam, String.class);
                } catch (IOException e) {
                    throw new BizException("发送车位已满消息出错", e);
                }
                // 车位已满，重载任务时间
                String planKey = TornConstants.SETTING_KEY_OC_PLAN_ID + param.rank();
                String excludePlanKey = TornConstants.SETTING_KEY_OC_PLAN_ID + param.excludeRank();
                String recKey = TornConstants.SETTING_KEY_OC_REC_ID + param.rank();
                String excludeRecKey = TornConstants.SETTING_KEY_OC_REC_ID + param.excludeRank();
                ocManager.refreshRotationSetting(TornConstants.FACTION_PN_ID, planKey, excludePlanKey,
                        recKey, excludeRecKey, param.enableRank());
                param.reloadSchedule().run();
            } else {
                sendLackMsg(!isNewOcCorrect, lackMap);
            }
        }

        /**
         * 发送OC队伍缺人的消息
         */
        private void sendLackMsg(boolean isLackNew, Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap) {
            Set<Long> userIdSet = ocUserManager.findRotationUser(TornConstants.FACTION_PN_ID, param.enableRank());
            int currentLackCount = lackMap.size() + (isLackNew ? 1 : 0);
            int lackCount = param.lackCount();
            int freeCount = param.freeCount();
            if (param.lackCount() == 0 || currentLackCount != param.lackCount() || userIdSet.size() != param.freeCount()) {
                lackCount = currentLackCount;
                freeCount = userIdSet.size();

                String noticeMsg = isLackNew ?
                        "还剩" + (lackMap.size() + 1) + "坑, 包含新队一坑\n" :
                        "还剩" + lackMap.size() + "坑\n";
                GroupMsgHttpBuilder msgBuilder = new GroupMsgHttpBuilder()
                        .setGroupId(projectProperty.getGroupId())
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
                            param.rank(), param.excludeRank(), param.refreshOc(), param.reloadSchedule(),
                            lackCount, freeCount, param.enableRank())),
                    LocalDateTime.now().plusMinutes(1L));
        }

        /**
         * 检测新队是否正常进入
         */
        private boolean checkNewOc(List<TornFactionOcDO> recList) {
            // 之前就有人的队伍不会进错，没有新队的时候直接返回判断
            String hasUserStr = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_REC_ID + param.rank());
            List<Long> hasUserIdList = Arrays.stream(hasUserStr.split(",")).map(Long::parseLong).toList();
            List<TornFactionOcDO> newTeamList = recList.stream().filter(o -> !hasUserIdList.contains(o.getId())).toList();
            if (CollectionUtils.isEmpty(newTeamList)) {
                return false;
            }

            boolean isNewOcCorrect = true;
            // 进入了不能进的级别
            String blockRankStr = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_BLOCK_RANK + param.rank());
            int blockRank = Integer.parseInt(blockRankStr);
            List<TornFactionOcDO> blockList = newTeamList.stream().filter(o -> o.getRank().equals(blockRank)).toList();
            if (!CollectionUtils.isEmpty(blockList)) {
                isNewOcCorrect = false;
                List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(blockList);
                GroupMsgHttpBuilder msgBuilder = new GroupMsgHttpBuilder()
                        .setGroupId(projectProperty.getGroupId())
                        .addMsg(new TextQqMsg("进错队啦，现在" + blockRank + "级OC不能参加"))
                        .addMsg(msgManager.buildSlotMsg(slotList));
                bot.sendRequest(msgBuilder.build(), String.class);
            }
            // 一天只能进一个新队，新队大于1个说明进多了
            if (newTeamList.size() > 1) {
                isNewOcCorrect = false;
                List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(newTeamList)
                        .stream().filter(s -> s.getUserId() != null).toList();
                List<QqMsgParam<?>> atMsgList = msgManager.buildSlotMsg(slotList);

                GroupMsgHttpBuilder msgBuilder = new GroupMsgHttpBuilder()
                        .setGroupId(projectProperty.getGroupId())
                        .addMsg(new TextQqMsg("新队进入重复啦\n"));
                for (int i = 0; i < slotList.size(); i++) {
                    msgBuilder.addMsg(atMsgList.get(i))
                            .addMsg(new TextQqMsg("在" + DateTimeUtils.convertToString(slotList.get(i).getJoinTime()) + "加入\n"));
                }
                bot.sendRequest(msgBuilder.build(), String.class);
            }

            return isNewOcCorrect;
        }
    }
}