package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserLogTypeEnum;
import pn.torn.goldeneye.napcat.receive.apply.GroupSysMsgJoinRec;
import pn.torn.goldeneye.napcat.receive.apply.GroupSysMsgRec;
import pn.torn.goldeneye.napcat.send.AuditJoinGroupReqParam;
import pn.torn.goldeneye.napcat.send.GroupSysMsgReqParam;
import pn.torn.goldeneye.napcat.send.KickGroupMemberReqParam;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.VipPayRecordDAO;
import pn.torn.goldeneye.repository.dao.setting.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.VipPayRecordDO;
import pn.torn.goldeneye.repository.model.setting.VipSubscribeDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.user.log.TornUserLogDTO;
import pn.torn.goldeneye.torn.model.user.log.TornUserLogVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VIP订阅公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VipSubscribeManager {
    private final Bot bot;
    private final TornApi tornApi;
    private final TornUserManager userManager;
    private final VipSubscribeDAO subscribeDao;
    private final VipPayRecordDAO payRecordDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    /**
     * VIP时长追加`
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void vipLengthAppend() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_ITEM_RECEIVE_LOG_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDateTime(value);
        LocalDateTime to = LocalDateTime.now();

        spiderPayRecord(from, to);
        exchangeVipLength();
        applyJoin();

        settingDao.updateSetting(SettingConstants.KEY_ITEM_RECEIVE_LOG_LOAD, DateTimeUtils.convertToString(to));
    }

    /**
     * 警告和踢出VIP成员
     */
    @Scheduled(cron = "0 0 8 */1 * ?")
    public void warnAndKickVipGroupMember() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        List<VipSubscribeDO> limitList = subscribeDao.lambdaQuery()
                .lt(VipSubscribeDO::getEndDate, LocalDate.now().plusDays(5L))
                .list();
        if (CollectionUtils.isEmpty(limitList)) {
            return;
        }

        List<Long> warningQqList = new ArrayList<>();
        for (VipSubscribeDO subscribe : limitList) {
            if (subscribe.getEndDate().isBefore(LocalDate.now())) {
                bot.sendRequest(new KickGroupMemberReqParam(
                        projectProperty.getVipGroupId(), subscribe.getQqId()), String.class);
                subscribeDao.removeById(subscribe.getId());
            } else {
                warningQqList.add(subscribe.getQqId());
            }
        }

        if (!warningQqList.isEmpty()) {
            List<AtQqMsg> atList = warningQqList.stream().map(AtQqMsg::new).toList();
            TextQqMsg warningMsg = new TextQqMsg("\n大佬们的订阅即将在5天内到期, 如果还满意请发送2Xan到3312605并备注"
                    + TornConstants.REMARK_SUBSCRIBE + "进行续费\n如不需要续费到期后机器人会自动将大佬移出群聊, 欢迎留下您宝贵的改进意见");
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getVipGroupId())
                    .addMsg(atList).addMsg(warningMsg).build();
            bot.sendRequest(param, String.class);
        }
    }

    /**
     * 爬取支付记录
     */
    private void spiderPayRecord(LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornUserLogDTO param;
        TornUserLogVO resp;
        LocalDateTime queryTo = to;
        List<VipPayRecordDO> payList = new ArrayList<>();
        TornApiKeyDO key = new TornApiKeyDO(0L, projectProperty.getScanVipToken());

        do {
            param = new TornUserLogDTO(TornUserLogTypeEnum.ITEM_RECEIVE, from, queryTo, limit);
            resp = tornApi.sendRequest(param, key, TornUserLogVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getLog())) {
                break;
            }

            resp.getLog().stream()
                    .filter(l -> TornConstants.VALID_SUBSCRIBE.equalsIgnoreCase(
                            l.getData().getMessage().replace("-", "").replace(" ", "")))
                    .forEach(l -> payList.addAll(l.convert2DO(userManager)));
            List<VipPayRecordDO> dataLit = buildDataList(payList);
            if (!CollectionUtils.isEmpty(dataLit)) {
                payRecordDao.saveBatch(dataLit);
            }

            queryTo = DateTimeUtils.convertToDateTime(resp.getLog().getLast().getTimestamp());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (resp.getLog().size() >= limit);
    }

    /**
     * 兑换VIP时长
     */
    private void exchangeVipLength() {
        List<VipPayRecordDO> balanceList = payRecordDao.lambdaQuery()
                .eq(VipPayRecordDO::getItemId, 206)
                .gt(VipPayRecordDO::getRemainQty, 0)
                .isNotNull(VipPayRecordDO::getQqId)
                .orderByAsc(VipPayRecordDO::getLogTime)
                .list();
        if (CollectionUtils.isEmpty(balanceList)) {
            return;
        }

        log.info("开始处理VIP时长兑换，待处理记录数: {}", balanceList.size());
        Map<Long, List<VipPayRecordDO>> userRecordsMap = balanceList.stream()
                .collect(Collectors.groupingBy(VipPayRecordDO::getUserId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, Integer> userVipDaysMap = new HashMap<>();
        List<VipPayRecordDO> recordsToUpdate = new ArrayList<>();
        for (Map.Entry<Long, List<VipPayRecordDO>> entry : userRecordsMap.entrySet()) {
            long userId = entry.getKey();
            List<VipPayRecordDO> userRecords = entry.getValue();

            int vipDays = calcVipDays(userId, userRecords, recordsToUpdate);
            if (vipDays > 0) {
                userVipDaysMap.put(userId, vipDays);
            }
        }

        if (!recordsToUpdate.isEmpty()) {
            boolean updateSuccess = payRecordDao.updateBatchById(recordsToUpdate);
            if (!updateSuccess) {
                log.error("批量更新VIP支付记录失败");
                throw new BizException("批量更新VIP支付记录失败");
            }
            log.info("成功更新 {} 条VIP支付记录", recordsToUpdate.size());
        }

        saveVipLength(userVipDaysMap);
        log.info("VIP时长兑换完成，共 {} 个用户获得时长", userVipDaysMap.size());
    }

    /**
     * 批准加群申请
     */
    private void applyJoin() {
        ResponseEntity<GroupSysMsgRec> resp = bot.sendRequest(new GroupSysMsgReqParam(), GroupSysMsgRec.class);
        List<GroupSysMsgJoinRec> applyList = resp.getBody().getData().getJoinRequests().stream()
                .filter(m -> projectProperty.getVipGroupId() == m.getGroupId())
                .filter(m -> !m.isChecked())
                .toList();
        if (CollectionUtils.isEmpty(applyList)) {
            return;
        }

        List<Long> qqIdList = applyList.stream().map(GroupSysMsgJoinRec::getInvitorUin).toList();
        List<VipSubscribeDO> subscribeList = subscribeDao.lambdaQuery().in(VipSubscribeDO::getQqId, qqIdList).list();
        for (GroupSysMsgJoinRec apply : applyList) {
            VipSubscribeDO subscribe = subscribeList.stream()
                    .filter(s -> s.getQqId().equals(apply.getInvitorUin())).findAny().orElse(null);
            if (subscribe == null) {
                continue;
            }

            bot.sendRequest(new AuditJoinGroupReqParam(apply.getRequestId()), String.class);
            subscribe.setStartDate(LocalDate.now());
            subscribe.setEndDate(LocalDate.now().plusDays(subscribe.getSubscribeLength()));
            subscribeDao.updateById(subscribe);
        }
    }

    /**
     * 构建可以插入的数据列表
     */
    private List<VipPayRecordDO> buildDataList(List<VipPayRecordDO> recordList) {
        if (CollectionUtils.isEmpty(recordList)) {
            return List.of();
        }

        List<String> logIdList = recordList.stream().map(VipPayRecordDO::getLogId).toList();
        List<VipPayRecordDO> oldDataList = payRecordDao.lambdaQuery().in(VipPayRecordDO::getLogId, logIdList).list();
        List<String> oldLogIdList = oldDataList.stream().map(VipPayRecordDO::getLogId).toList();

        return recordList.stream()
                .filter(r -> !oldLogIdList.contains(r.getLogId()))
                .toList();
    }

    /**
     * 计算VIP时长
     */
    private int calcVipDays(long userId, List<VipPayRecordDO> userRecords, List<VipPayRecordDO> recordsToUpdate) {
        int totalRemainQty = userRecords.stream()
                .mapToInt(VipPayRecordDO::getRemainQty)
                .sum();
        int exchangeCount = totalRemainQty / 2;
        int vipDays = exchangeCount * 31;
        int needDeductQty = exchangeCount * 2;

        if (exchangeCount == 0) {
            log.debug("用户 {} 道具数量不足5个，暂不兑换，剩余数量: {}", userId, totalRemainQty);
            return 0;
        }

        log.info("用户 {} 可兑换 {} 次，增加 {} 天VIP，消耗道具 {} 个，剩余 {} 个",
                userId, exchangeCount, vipDays, needDeductQty, totalRemainQty - needDeductQty);
        int remainingToDeduct = needDeductQty;
        for (VipPayRecordDO payRecord : userRecords) {
            if (remainingToDeduct <= 0) {
                break;
            }

            int currentRemain = payRecord.getRemainQty();
            if (currentRemain <= remainingToDeduct) {
                // 当前记录的道具全部消耗
                payRecord.setRemainQty(0);
                remainingToDeduct -= currentRemain;
                log.debug("记录 {} 全部消耗，原剩余: {}", payRecord.getId(), currentRemain);
            } else {
                // 当前记录的道具部分消耗
                payRecord.setRemainQty(currentRemain - remainingToDeduct);
                log.debug("记录 {} 部分消耗，原剩余: {}，扣除: {}，新剩余: {}",
                        payRecord.getId(), currentRemain, remainingToDeduct, payRecord.getRemainQty());
                remainingToDeduct = 0;
            }

            recordsToUpdate.add(payRecord);
        }
        return vipDays;
    }

    /**
     * 保存VIP时长
     */
    private void saveVipLength(Map<Long, Integer> userVipDaysMap) {
        if (CollectionUtils.isEmpty(userVipDaysMap)) {
            return;
        }

        List<VipSubscribeDO> subscribeList = subscribeDao.lambdaQuery()
                .in(VipSubscribeDO::getUserId, userVipDaysMap.keySet())
                .list();
        List<VipSubscribeDO> newDataList = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : userVipDaysMap.entrySet()) {
            VipSubscribeDO subscribe = subscribeList.stream()
                    .filter(s -> s.getUserId().equals(entry.getKey())).findAny().orElse(null);
            if (subscribe != null) {
                subscribe.setSubscribeLength(subscribe.getSubscribeLength() + entry.getValue());
                if (subscribe.getEndDate() != null) {
                    subscribe.setEndDate(subscribe.getEndDate().plusDays(subscribe.getSubscribeLength()));
                }
                subscribeDao.updateById(subscribe);
            } else {
                TornUserDO user = userManager.getUserById(entry.getKey());
                newDataList.add(new VipSubscribeDO(user, entry.getValue()));
            }
        }

        if (!CollectionUtils.isEmpty(newDataList)) {
            subscribeDao.saveBatch(newDataList);
        }
    }
}