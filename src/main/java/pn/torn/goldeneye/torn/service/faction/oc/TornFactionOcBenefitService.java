package pn.torn.goldeneye.torn.service.faction.oc;

import com.lark.oapi.service.bitable.v1.enums.ConditionOperatorEnum;
import com.lark.oapi.service.bitable.v1.enums.FilterInfoConjunctionEnum;
import com.lark.oapi.service.bitable.v1.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteBitTableProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.larksuite.LarkSuiteUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Torn OC收益逻辑层
 *
 * @author Bai
 * @version 1.2.2
 * @since 2025.09.08
 */
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_OC_BENEFIT)
@Slf4j
public class TornFactionOcBenefitService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final LarkSuiteApi larkSuiteApi;
    private final TornSettingFactionManager factionManager;
    private final TornFactionOcBenefitDAO benefitDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;
    private final LarkSuiteProperty larkSuiteProperty;
    private Map<String, TornSettingFactionDO> factionMap;
    // 飞书表格中的字段名常量
    private static final String FIELD_OC_STATUS = "当前状态";
    private static final String FIELD_OC_ID = "OCID";
    private static final String FIELD_OC_NAME = "OC名称";
    private static final String FIELD_PREVIOUS = "previous";
    private static final String FIELD_NEXT = "next";
    private static final String FIELD_FINISH_TIME = "实际完成时间";
    private static final String FIELD_USER_IDS_STRING = "参与人id字符串";
    private static final String FIELD_BONUS_STRING = "个人奖金";
    private static final String FIELD_OC_RANK = "难度等级";
    private static final String PREFIX_SLOT = "slot_";

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        factionMap = factionManager.getAliasMap();
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String lastRefreshTime = settingDao.querySettingValue(SettingConstants.KEY_OC_BENEFIT_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDateTime(lastRefreshTime);
        LocalDateTime to = LocalDateTime.now();
        if (from.plusHours(1).isBefore(LocalDateTime.now())) {
            virtualThreadExecutor.execute(() -> spiderOcBenefit(from, to));
        } else {
            addScheduleTask(from);
        }
    }

    /**
     * 爬取OC收益
     */
    public void spiderOcBenefit(LocalDateTime from, LocalDateTime to) {
        try {
            doSpiderOcBenefit(from, to);
        } catch (Exception e) {
            log.error("OC收益采集失败, from={}, to={}",
                    DateTimeUtils.convertToString(from), DateTimeUtils.convertToString(to), e);
            addRetryTask(from);
        }
    }

    /**
     * 爬取OC收益
     */
    private void doSpiderOcBenefit(LocalDateTime from, LocalDateTime to) {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_OC_BENEFIT);
        String pageToken = null;
        boolean hasMore;

        do {
            final String finalPageToken = pageToken;
            SearchAppTableRecordRespBody response = larkSuiteApi.sendRequest(client -> {
                SearchAppTableRecordReq.Builder reqBuilder = SearchAppTableRecordReq.newBuilder()
                        .appToken(bitTable.getAppToken())
                        .tableId(bitTable.getTableId())
                        .pageSize(100)
                        .searchAppTableRecordReqBody(SearchAppTableRecordReqBody.newBuilder()
                                .viewId(bitTable.getViewId()).filter(FilterInfo.newBuilder()
                                        .conjunction(FilterInfoConjunctionEnum.CONJUNCTIONAND)
                                        .conditions(new Condition[]{Condition.newBuilder()
                                                .fieldName(FIELD_FINISH_TIME)
                                                .operator(ConditionOperatorEnum.OPERATORISGREATER)
                                                .value(new String[]{"ExactDate",
                                                        String.valueOf(DateTimeUtils.convertToTimestamp(from.minusHours(1)))})
                                                .build()})
                                        .build())
                                .build());
                if (StringUtils.hasText(finalPageToken)) {
                    reqBuilder.pageToken(finalPageToken);
                }
                return client.bitable().v1().appTableRecord().search(reqBuilder.build());
            });

            if (response == null || ArrayUtils.isEmpty(response.getItems())) {
                break;
            }

            List<TornFactionOcBenefitDO> benefitList = parseRecords(response.getItems());
            saveBenefitList(benefitList);
            hasMore = response.getHasMore();
            pageToken = response.getPageToken();
        } while (hasMore);

        settingDao.updateSetting(SettingConstants.KEY_OC_BENEFIT_LOAD, DateTimeUtils.convertToString(to));
        addScheduleTask(to);
    }

    /**
     * 保存收益列表
     */
    public void saveBenefitList(List<TornFactionOcBenefitDO> benefitList) {
        if (CollectionUtils.isEmpty(benefitList)) {
            return;
        }

        List<TornFactionOcBenefitDO> distinctBenefitList = distinctBenefitList(benefitList);
        Set<Long> ocIdSet = distinctBenefitList.stream().map(TornFactionOcBenefitDO::getOcId).collect(Collectors.toSet());
        Set<OcBenefitKey> existKeySet = benefitDao.lambdaQuery()
                .in(TornFactionOcBenefitDO::getOcId, ocIdSet)
                .list()
                .stream()
                .map(b -> new OcBenefitKey(b.getOcId(), b.getUserId()))
                .collect(Collectors.toSet());

        List<TornFactionOcBenefitDO> newDataList = distinctBenefitList.stream()
                .filter(b -> !existKeySet.contains(new OcBenefitKey(b.getOcId(), b.getUserId())))
                .toList();
        if (!CollectionUtils.isEmpty(newDataList)) {
            benefitDao.saveBatch(newDataList);
        }
    }

    /**
     * 按唯一键去重收益列表
     */
    private List<TornFactionOcBenefitDO> distinctBenefitList(List<TornFactionOcBenefitDO> benefitList) {
        Map<OcBenefitKey, TornFactionOcBenefitDO> benefitMap = new LinkedHashMap<>();
        for (TornFactionOcBenefitDO benefit : benefitList) {
            OcBenefitKey key = new OcBenefitKey(benefit.getOcId(), benefit.getUserId());
            TornFactionOcBenefitDO oldBenefit = benefitMap.putIfAbsent(key, benefit);
            if (oldBenefit != null) {
                log.warn("OC收益批次内出现重复记录, ocId={}, userId={}, oldBenefit={}, newBenefit={}",
                        key.ocId(), key.userId(), oldBenefit.getBenefitMoney(), benefit.getBenefitMoney());
            }
        }
        return new ArrayList<>(benefitMap.values());
    }

    /**
     * 解析一批从 API 获取的记录
     */
    private List<TornFactionOcBenefitDO> parseRecords(AppTableRecord[] items) {
        List<TornFactionOcBenefitDO> benefits = new ArrayList<>();
        for (AppTableRecord item : items) {
            try {
                benefits.addAll(parseSingleRecord(item));
            } catch (Exception e) {
                log.error("解析OC收益记录失败, recordId={}, fields={}", item == null ? null : item.getRecordId(),
                        item == null ? null : item.getFields(), e);
            }
        }
        return benefits;
    }

    /**
     * 解析单条飞书表格记录
     */
    private List<TornFactionOcBenefitDO> parseSingleRecord(AppTableRecord item) {
        if (item == null || item.getFields() == null) {
            return List.of();
        }
        Map<String, Object> fields = item.getFields();
        String ocStatus = LarkSuiteUtils.getTextFieldValue(fields, FIELD_OC_STATUS);
        Number previousOcIdNum = (Number) fields.get(FIELD_PREVIOUS);
        Number nextOcIdNum = (Number) fields.get(FIELD_NEXT);

        // 复杂情况：一个成功的OC，并且它有一个前置OC。奖金合并计算。
        if ("Successful".equals(ocStatus) && previousOcIdNum != null && nextOcIdNum == null) {
            return handleSuccessChainOc(fields, previousOcIdNum.longValue());
        } else {
            // 标准情况：独立的OC，或失败的OC。
            return handleStandardOc(fields);
        }
    }

    /**
     * 处理奖金合并的成功 OC
     */
    private List<TornFactionOcBenefitDO> handleSuccessChainOc(Map<String, Object> currentFields, long previousOcId) {
        List<AppTableRecord> ocChain = getOcChain(currentFields, previousOcId);
        if (ocChain.isEmpty()) {
            return List.of();
        }

        List<OcChainJoinUser> joinUserList = buildChainJoinUserList(ocChain);
        String[] userIds = LarkSuiteUtils.getTextFieldValue(currentFields, FIELD_USER_IDS_STRING).split(",");
        String[] benefits = LarkSuiteUtils.getTextFieldValue(currentFields, FIELD_BONUS_STRING).split(",");
        if (!isValidBenefitPayload(userIds, benefits)) {
            return List.of();
        }

        return buildChainBenefits(currentFields, joinUserList, userIds, benefits);
    }

    /**
     * 构建链式OC参与人列表
     */
    private List<OcChainJoinUser> buildChainJoinUserList(List<AppTableRecord> ocChain) {
        List<OcChainJoinUser> joinUserList = new ArrayList<>();
        for (AppTableRecord data : ocChain) {
            Map<String, Object> fields = data.getFields();
            for (OcJoinUser user : extractUserByFields(fields)) {
                joinUserList.add(new OcChainJoinUser(fields, user));
            }
        }
        return joinUserList;
    }

    /**
     * 校验链式OC的奖金载荷
     */
    private boolean isValidBenefitPayload(String[] userIds, String[] benefits) {
        return !ArrayUtils.isEmpty(userIds) && benefits.length == userIds.length;
    }

    /**
     * 构建链式OC收益结果
     */
    private List<TornFactionOcBenefitDO> buildChainBenefits(Map<String, Object> currentFields,
                                                            List<OcChainJoinUser> joinUserList,
                                                            String[] userIds,
                                                            String[] benefits) {
        List<TornFactionOcBenefitDO> benefitList = new ArrayList<>();
        List<OcChainJoinUser> remainingJoinUserList = new ArrayList<>(joinUserList);
        for (int i = 0; i < userIds.length; i++) {
            long userId = Long.parseLong(userIds[i].trim());
            OcChainJoinUser chainUser = findAndRemoveChainUser(remainingJoinUserList, userId);
            TornFactionOcBenefitDO benefit = createBaseDO(chainUser == null ? currentFields : chainUser.fields());
            fillChainBenefit(benefit, chainUser, userId, benefits[i]);
            benefitList.add(benefit);
        }
        return benefitList;
    }

    /**
     * 查找并移除链式OC参与人
     */
    private OcChainJoinUser findAndRemoveChainUser(List<OcChainJoinUser> joinUserList, long userId) {
        for (int i = 0; i < joinUserList.size(); i++) {
            OcChainJoinUser chainUser = joinUserList.get(i);
            if (chainUser.user().userId() == userId) {
                return joinUserList.remove(i);
            }
        }
        return null;
    }

    /**
     * 填充链式OC收益结果
     */
    private void fillChainBenefit(TornFactionOcBenefitDO benefit, OcChainJoinUser chainUser,
                                  long userId, String benefitValue) {
        if (chainUser != null) {
            OcJoinUser details = chainUser.user();
            benefit.setUserId(details.userId());
            benefit.setUserPosition(details.position());
            benefit.setUserPassRate((int) (details.chance() * 100));
            benefit.setItemCost(details.itemCost());
        } else {
            benefit.setUserId(userId);
            benefit.setItemCost(0L);
        }
        benefit.setBenefitMoney(Long.parseLong(benefitValue.trim()));
        benefit.setNetReward(benefit.getBenefitMoney() - benefit.getItemCost());
    }

    /**
     * 获取完整OC链
     */
    private List<AppTableRecord> getOcChain(Map<String, Object> currentFields, long previousOcId) {
        List<AppTableRecord> ocChain = new ArrayList<>();
        AppTableRecord currentRecord = new AppTableRecord();
        currentRecord.setFields(currentFields);
        ocChain.add(currentRecord);

        Long currentPreviousId = previousOcId;
        while (currentPreviousId != null) {
            AppTableRecord previousRecord = findOcRecordById(currentPreviousId);
            if (previousRecord == null || previousRecord.getFields() == null) {
                log.warn("未找到链式OC前置记录, previousOcId={}", currentPreviousId);
                break;
            }
            ocChain.addFirst(previousRecord);
            Number previous = (Number) previousRecord.getFields().get(FIELD_PREVIOUS);
            currentPreviousId = previous == null ? null : previous.longValue();
        }
        return ocChain;
    }

    /**
     * 处理标准 OC（独立的或Chain失败的）
     */
    private List<TornFactionOcBenefitDO> handleStandardOc(Map<String, Object> fields) {
        // 对于标准OC，每次都使用一个新的岗位计数器
        List<OcJoinUser> userList = extractUserByFields(fields);
        if (userList.isEmpty()) {
            return List.of();
        }

        // 构建 UserId -> Bonus 的映射，以便快速查找
        String[] userIds = LarkSuiteUtils.getTextFieldValue(fields, FIELD_USER_IDS_STRING).split(",");
        String[] benefits = LarkSuiteUtils.getTextFieldValue(fields, FIELD_BONUS_STRING).split(",");
        if (benefits.length < 2) {
            return List.of();
        }

        Map<Long, Long> benefitMap = new HashMap<>();
        IntStream.range(0, userIds.length).forEach(i ->
                benefitMap.put(Long.parseLong(userIds[i].trim()), Long.parseLong(benefits[i].trim())));

        List<TornFactionOcBenefitDO> benefitList = new ArrayList<>();
        for (OcJoinUser details : userList) {
            TornFactionOcBenefitDO benefitDO = createBaseDO(fields);
            benefitDO.setUserId(details.userId());
            benefitDO.setUserPosition(details.position());
            benefitDO.setUserPassRate((int) (details.chance() * 100));
            // 从Map中查找该用户的奖金，找不到则默认为0
            benefitDO.setBenefitMoney(benefitMap.getOrDefault(details.userId(), 0L));
            benefitDO.setItemCost(details.itemCost());
            benefitDO.setNetReward(benefitDO.getBenefitMoney() - details.itemCost());
            benefitList.add(benefitDO);
        }
        return benefitList;
    }

    /**
     * 从一条记录的fields中，按slot顺序提取所有参与人的信息
     */
    private List<OcJoinUser> extractUserByFields(Map<String, Object> fields) {
        List<OcJoinUser> participants = new ArrayList<>();
        Map<String, Integer> positionCounts = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            Number userIdNum = (Number) fields.get(PREFIX_SLOT + i + "_user_id");
            String originalPosition = LarkSuiteUtils.getTextFieldValue(fields, PREFIX_SLOT + i + "_position");
            // 如果岗位没人，则跳过此岗位
            if (userIdNum == null || !StringUtils.hasText(originalPosition)) {
                continue;
            }

            // 递增并获取当前岗位的计数
            int count = positionCounts.getOrDefault(originalPosition, 0) + 1;
            positionCounts.put(originalPosition, count);
            // 格式化岗位名称，例如 "Muscle" -> "Muscle#1"
            String formattedPosition = originalPosition + "#" + count;
            Number chanceNum = (Number) fields.get(PREFIX_SLOT + i + "_chance");
            double chance = (chanceNum != null) ? chanceNum.doubleValue() : 0.0;
            Number itemCostNum = (Number) fields.get(PREFIX_SLOT + i + "_item_marketvalue");
            long itemCost = (itemCostNum != null) ? itemCostNum.longValue() : 0L;

            participants.add(new OcJoinUser(userIdNum.longValue(), formattedPosition, chance, itemCost));
        }
        return participants;
    }

    /**
     * 创建并填充一个基础的DO对象，包含OC的公共信息
     */
    private TornFactionOcBenefitDO createBaseDO(Map<String, Object> fields) {
        TornFactionOcBenefitDO benefit = new TornFactionOcBenefitDO();
        benefit.setFactionId(factionMap.get(fields.get("帮派").toString()).getId());
        benefit.setOcId(((Number) fields.get(FIELD_OC_ID)).longValue());
        benefit.setOcName(fields.get(FIELD_OC_NAME).toString());
        benefit.setOcRank(((Number) fields.get(FIELD_OC_RANK)).intValue());
        benefit.setOcStatus(LarkSuiteUtils.getTextFieldValue(fields, FIELD_OC_STATUS));

        Number finishTimeNum = (Number) fields.get(FIELD_FINISH_TIME);
        if (finishTimeNum != null) {
            benefit.setOcFinishTime(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(finishTimeNum.longValue()), ZoneId.systemDefault())
            );
        }
        return benefit;
    }

    /**
     * 根据OC ID查找单条多维表格记录
     */
    private AppTableRecord findOcRecordById(long ocId) {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_OC_BENEFIT);
        SearchAppTableRecordRespBody response = larkSuiteApi.sendRequest(client -> {
            SearchAppTableRecordReq req = SearchAppTableRecordReq.newBuilder()
                    .appToken(bitTable.getAppToken())
                    .tableId(bitTable.getTableId())
                    .searchAppTableRecordReqBody(SearchAppTableRecordReqBody.newBuilder()
                            .viewId(bitTable.getViewId())
                            .filter(FilterInfo.newBuilder()
                                    .conjunction(FilterInfoConjunctionEnum.CONJUNCTIONAND)
                                    .conditions(new Condition[]{Condition.newBuilder()
                                            .fieldName(FIELD_OC_ID)
                                            .operator(ConditionOperatorEnum.OPERATORIS)
                                            .value(new String[]{String.valueOf(ocId)})
                                            .build()})
                                    .build())
                            .build())
                    .build();
            return client.bitable().v1().appTableRecord().search(req);
        });

        if (response != null && !ArrayUtils.isEmpty(response.getItems())) {
            return response.getItems()[0];
        }
        return null;
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("oc-benefit-reload", () -> spiderOcBenefit(to, to.plusHours(1)), to.plusHours(1));
    }

    /**
     * 添加重试任务
     */
    private void addRetryTask(LocalDateTime from) {
        LocalDateTime retryTime = LocalDateTime.now().plusMinutes(10);
        taskService.updateTask("oc-benefit-reload", () -> spiderOcBenefit(from, LocalDateTime.now()), retryTime);
    }

    private record OcJoinUser(long userId, String position, double chance, long itemCost) {
    }

    private record OcChainJoinUser(Map<String, Object> fields, OcJoinUser user) {
    }

    private record OcBenefitKey(Long ocId, Long userId) {
    }
}