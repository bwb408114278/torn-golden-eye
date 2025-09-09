package pn.torn.goldeneye.torn.service.faction.oc;

import com.lark.oapi.service.bitable.v1.model.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
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
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.larksuite.LarkSuiteUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Torn OC收益逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.08
 */
@Service
@RequiredArgsConstructor
@Order(10005)
public class TornFactionOcBenefitService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final LarkSuiteApi larkSuiteApi;
    private final TornFactionOcBenefitDAO benefitDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;
    private final LarkSuiteProperty larkSuiteProperty;
    // 飞书表格中的字段名常量
    private static final String FIELD_OC_STATUS = "当前状态";
    private static final String FIELD_OC_ID = "OCID";
    private static final String FIELD_OC_NAME = "OC名称";
    private static final String FIELD_PREVIOUS = "previous";
    private static final String FIELD_FINISH_TIME = "实际完成时间";
    private static final String FIELD_USER_IDS_STRING = "参与人id字符串";
    private static final String FIELD_BONUS_STRING = "个人奖金";
    private static final String PREFIX_SLOT = "slot_";

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_BENEFIT_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(() -> spiderOcBenefit(from, to));
        }

        addScheduleTask(to);
    }

    /**
     * 爬取OC收益
     */
    public void spiderOcBenefit(LocalDateTime from, LocalDateTime to) {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_OC_BENEFIT);
        String pageToken = null;
        boolean hasMore;

        do {
            final String finalPageToken = pageToken;
            SearchAppTableRecordRespBody response = larkSuiteApi.sendRequest(client -> {
                SearchAppTableRecordReq.Builder reqBuilder = SearchAppTableRecordReq.newBuilder()
                        .appToken(bitTable.getAppToken())
                        .tableId(bitTable.getTableId())
                        .pageSize(100);
                if (StringUtils.hasText(finalPageToken)) {
                    reqBuilder.pageToken(finalPageToken);
                }
                return client.bitable().v1().appTableRecord().search(reqBuilder.build());
            });

            if (response == null || ArrayUtils.isEmpty(response.getItems())) {
                break;
            }

            List<TornFactionOcBenefitDO> benefitList = parseRecords(response.getItems());
            if (benefitList.stream().anyMatch(b -> !b.getOcFinishTime().isBefore(from))) {
                saveBenefitList(benefitList, from);
                hasMore = response.getHasMore();
                pageToken = response.getPageToken();
            } else {
                hasMore = false;
            }

        } while (hasMore);

        settingDao.updateSetting(TornConstants.SETTING_KEY_OC_PASS_RATE_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 保存收益列表
     */
    public void saveBenefitList(List<TornFactionOcBenefitDO> benefitList, LocalDateTime from) {
        if (CollectionUtils.isEmpty(benefitList)) {
            return;
        }

        Set<Long> ocIdSet = benefitList.stream().map(TornFactionOcBenefitDO::getOcId).collect(Collectors.toSet());
        Set<Long> existOcIdSet = benefitDao.lambdaQuery()
                .in(TornFactionOcBenefitDO::getOcId, ocIdSet)
                .list()
                .stream()
                .map(TornFactionOcBenefitDO::getOcId)
                .collect(Collectors.toSet());

        List<TornFactionOcBenefitDO> newDataList = benefitList.stream()
                .filter(b -> !existOcIdSet.contains(b.getOcId()) && !b.getOcFinishTime().isBefore(from))
                .toList();
        if (!CollectionUtils.isEmpty(newDataList)) {
            benefitDao.saveBatch(newDataList);
        }
    }

    /**
     * 解析一批从 API 获取的记录
     */
    private List<TornFactionOcBenefitDO> parseRecords(AppTableRecord[] items) {
        List<TornFactionOcBenefitDO> benefits = new ArrayList<>();
        for (AppTableRecord item : items) {
            benefits.addAll(parseSingleRecord(item));
        }
        return benefits;
    }

    /**
     * 解析单条飞书表格记录
     */
    private List<TornFactionOcBenefitDO> parseSingleRecord(AppTableRecord item) {
        if (item == null || item.getFields() == null) {
            return Collections.emptyList();
        }
        Map<String, Object> fields = item.getFields();
        String ocStatus = LarkSuiteUtils.getTextFieldValue(fields, FIELD_OC_STATUS);
        Number previousOcIdNum = (Number) fields.get(FIELD_PREVIOUS);

        // 复杂情况：一个成功的OC，并且它有一个前置OC。奖金合并计算。
        if ("Successful".equals(ocStatus) && previousOcIdNum != null) {
            return handleSuccessfulCombinedOc(fields, previousOcIdNum.longValue());
        } else {
            // 标准情况：独立的OC，或失败的OC。
            return handleStandardOc(fields);
        }
    }

    /**
     * 处理奖金合并的成功 OC
     */
    private List<TornFactionOcBenefitDO> handleSuccessfulCombinedOc(Map<String, Object> currentFields, long previousOcId) {
        // 1. 通过 API 请求获取前置 OC 的记录
        AppTableRecord previousRecord = findOcRecordById(previousOcId);
        if (previousRecord == null) {
            return List.of();
        }

        // 2. 分别提取前置和当前 OC 的参与人详情（按 slot 顺序）
        // 创建一个共享的计数器map，确保岗位编号在前后两个OC之间是连续的
        Map<String, Integer> combinedPositionCounts = new HashMap<>();
        List<ParticipantDetails> previousParticipants = extractParticipantsFromFields(previousRecord.getFields(), combinedPositionCounts);
        List<ParticipantDetails> currentParticipants = extractParticipantsFromFields(currentFields, combinedPositionCounts);

        // 3. 合并参与人列表
        List<ParticipantDetails> allParticipants = new ArrayList<>();
        allParticipants.addAll(previousParticipants);
        allParticipants.addAll(currentParticipants);

        // 4. 从当前 OC 记录中获取合并后的用户ID和奖金列表
        String[] combinedUserIds = StringUtils.split(LarkSuiteUtils.getTextFieldValue(currentFields, FIELD_USER_IDS_STRING), ",");
        String[] combinedBonuses = StringUtils.split(LarkSuiteUtils.getTextFieldValue(currentFields, FIELD_BONUS_STRING), ",");

        if (ArrayUtils.isEmpty(combinedUserIds) || allParticipants.size() != combinedUserIds.length) {
            return List.of();
        }

        // 5. 创建 DO 列表，将奖金逐一分配给合并后的参与人列表
        List<TornFactionOcBenefitDO> benefits = new ArrayList<>();
        for (int i = 0; i < allParticipants.size(); i++) {
            ParticipantDetails details = allParticipants.get(i);

            // 创建基础DO，使用当前OC（后置OC）的信息作为记录主体
            TornFactionOcBenefitDO benefitDO = createBaseDO(currentFields);
            benefitDO.setUserId(details.userId());
            benefitDO.setUserPosition(details.position());
            benefitDO.setUserPassRate((int) (details.chance() * 100));
            benefitDO.setBenefitMoney(Long.parseLong(combinedBonuses[i].trim()));
            benefits.add(benefitDO);
        }
        return benefits;
    }

    /**
     * 处理标准 OC（独立的或Chain失败的）
     */
    private List<TornFactionOcBenefitDO> handleStandardOc(Map<String, Object> fields) {
        // 对于标准OC，每次都使用一个新的岗位计数器
        List<ParticipantDetails> participants = extractParticipantsFromFields(fields, new HashMap<>());
        if (participants.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建 UserId -> Bonus 的映射，以便快速查找
        String[] userIds = StringUtils.split(LarkSuiteUtils.getTextFieldValue(fields, FIELD_USER_IDS_STRING), ",");
        String[] bonuses = StringUtils.split(LarkSuiteUtils.getTextFieldValue(fields, FIELD_BONUS_STRING), ",");
        Map<Long, Long> benefitMap = new HashMap<>();
        if (ArrayUtils.isNotEmpty(userIds) && userIds.length == bonuses.length) {
            IntStream.range(0, userIds.length).forEach(i ->
                    benefitMap.put(Long.parseLong(userIds[i].trim()), Long.parseLong(bonuses[i].trim())));
        }

        List<TornFactionOcBenefitDO> benefits = new ArrayList<>();
        for (ParticipantDetails details : participants) {
            TornFactionOcBenefitDO benefitDO = createBaseDO(fields);
            benefitDO.setUserId(details.userId());
            benefitDO.setUserPosition(details.position());
            benefitDO.setUserPassRate((int) (details.chance() * 100));
            // 从Map中查找该用户的奖金，找不到则默认为0
            benefitDO.setBenefitMoney(benefitMap.getOrDefault(details.userId(), 0L));
            benefits.add(benefitDO);
        }
        return benefits;
    }

    /**
     * 从一条记录的 fields 中，按slot顺序提取所有参与人的信息
     */
    private List<ParticipantDetails> extractParticipantsFromFields(Map<String, Object> fields, Map<String, Integer> positionCounts) {
        List<ParticipantDetails> participants = new ArrayList<>();
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

            participants.add(new ParticipantDetails(userIdNum.longValue(), formattedPosition, chance));
        }
        return participants;
    }

    /**
     * 创建并填充一个基础的DO对象，包含OC的公共信息
     */
    private TornFactionOcBenefitDO createBaseDO(Map<String, Object> fields) {
        TornFactionOcBenefitDO benefit = new TornFactionOcBenefitDO();
        benefit.setFactionId(TornConstants.FACTION_PN_ID);

        Number ocId = (Number) fields.get(FIELD_OC_ID);
        if (ocId != null) {
            benefit.setOcId(ocId.longValue());
        }

        benefit.setOcName(Objects.toString(fields.get(FIELD_OC_NAME), ""));
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
                    .searchAppTableRecordReqBody(SearchAppTableRecordReqBody.newBuilder().filter(FilterInfo.newBuilder()
                                    .conjunction("and")
                                    .conditions(new Condition[]{Condition.newBuilder()
                                            .fieldName(FIELD_OC_ID)
                                            .operator("is")
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
        taskService.updateTask("oc-benefit-reload",
                () -> spiderOcBenefit(to.plusSeconds(1), to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(5L));
    }

    private record ParticipantDetails(long userId, String position, double chance) {
    }
}