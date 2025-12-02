package pn.torn.goldeneye.torn.service.faction.armory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.TornItemTypeEnum;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.AtQqMsg;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_ITEM_USED)
@Slf4j
public class ItemUsedService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final Bot bot;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornApi tornApi;
    private final TornSettingFactionManager settingFactionManager;
    private final TornItemsManager itemsManager;
    private final TornFactionItemUsedDAO usedDao;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;
    private static final List<String> MISUSE_ITEM_LIST = new ArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        MISUSE_ITEM_LIST.add("Pixie Sticks");
        MISUSE_ITEM_LIST.add("Bottle of Moonshine");

        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_ITEM_USE_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            spiderItemUseData(from, to);
        }

        addScheduleTask(to);
    }

    /**
     * 爬取物品使用记录
     */
    public void spiderItemUseData(LocalDateTime from, LocalDateTime to) {
        List<TornSettingFactionDO> factionList = settingFactionManager.getList();
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (TornSettingFactionDO faction : factionList) {
            futureList.add(CompletableFuture.runAsync(() -> {
                        TornApiKeyDO key = apiKeyConfig.getFactionKey(faction.getId(), true);
                        if (key == null) {
                            return;
                        }

                        apiKeyConfig.returnKey(key);
                        spiderItemUseData(faction, from, to);
                    },
                    virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        settingDao.updateSetting(SettingConstants.KEY_ITEM_USE_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 爬取物品使用记录
     */
    public void spiderItemUseData(TornSettingFactionDO faction, LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionNewsDTO param;
        LocalDateTime queryTo = to;
        List<TornFactionItemUsedDO> newsList;
        List<TornFactionItemUsedDO> misuseList = new ArrayList<>();

        do {
            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.ARMORY_ACTION, from, queryTo, limit);
            TornFactionNewsListVO resp = tornApi.sendRequest(faction.getId(), param, TornFactionNewsListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getNews())) {
                break;
            }

            newsList = resp.getNews().stream()
                    .map(n -> n.convert2DO(itemsManager))
                    .toList();
            List<TornFactionItemUsedDO> dataList = buildDataList(newsList);
            if (!CollectionUtils.isEmpty(dataList)) {
                Set<String> nicknameSet = dataList.stream().map(TornFactionItemUsedDO::getUserNickname).collect(Collectors.toSet());
                Map<String, Long> nicknameMap = userDao.queryNicknameMap(nicknameSet);
                for (TornFactionItemUsedDO data : dataList) {
                    data.setFactionId(faction.getId());
                    data.setUserId(nicknameMap.get(data.getUserNickname()));
                    if (checkIsMisuse(data)) {
                        misuseList.add(data);
                    }
                }

                usedDao.saveBatch(dataList);
            }

            queryTo = DateTimeUtils.convertToDateTime(resp.getNews().getLast().getTimestamp());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (newsList.size() >= limit);

        sendWarningMsg(faction, misuseList);
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("item-use-reload",
                () -> spiderItemUseData(to.plusSeconds(1), to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(10L));
    }

    /**
     * 构建可以插入的数据列表
     */
    private List<TornFactionItemUsedDO> buildDataList(List<TornFactionItemUsedDO> newsList) {
        if (CollectionUtils.isEmpty(newsList)) {
            return List.of();
        }

        List<String> idList = newsList.stream().map(TornFactionItemUsedDO::getId).toList();
        List<TornFactionItemUsedDO> oldDataList = usedDao.lambdaQuery().in(TornFactionItemUsedDO::getId, idList).list();
        List<String> oldIdList = oldDataList.stream().map(TornFactionItemUsedDO::getId).toList();

        List<TornFactionItemUsedDO> resultList = new ArrayList<>();
        for (TornFactionItemUsedDO news : newsList) {
            boolean notValidType = !news.getUseType().equals("used") && !news.getUseType().equals("filled");
            boolean isOldData = oldIdList.contains(news.getId());
            boolean isItem = StringUtils.hasText(news.getItemName());
            if (notValidType || isOldData || !isItem) {
                continue;
            }

            resultList.add(news);
        }

        return resultList;
    }

    /**
     * 判断是否为误用物资
     */
    private boolean checkIsMisuse(TornFactionItemUsedDO usedItem) {
        TornItemsDO item = itemsManager.getNameMap().get(usedItem.getItemName());
        return TornItemTypeEnum.ENERGY_DRINK.getCode().equals(item.getItemType()) ||
                MISUSE_ITEM_LIST.contains(item.getItemName());
    }

    /**
     * 发送警告信息
     */
    private void sendWarningMsg(TornSettingFactionDO faction, List<TornFactionItemUsedDO> misuseList) {
        if (misuseList.isEmpty() || faction.getGroupId().equals(0L)) {
            return;
        }

        Map<String, List<TornFactionItemUsedDO>> userVsItemMap = HashMap.newHashMap(misuseList.size());
        for (TornFactionItemUsedDO item : misuseList) {
            String key = item.getUserId() + "#" + item.getUserNickname() + "#" + item.getItemName();
            List<TornFactionItemUsedDO> useList = userVsItemMap.get(key);
            if (CollectionUtils.isEmpty(useList)) {
                useList = new ArrayList<>();
                useList.add(item);
                userVsItemMap.put(key, useList);
            } else {
                useList.add(item);
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("\n昨天有人吃了帮派里的糖酒饮料, 请确认是否消耗了OC战利品或矿产: ");
        for (Map.Entry<String, List<TornFactionItemUsedDO>> entry : userVsItemMap.entrySet()) {
            String[] key = entry.getKey().split("#");
            String timeRangeDesc = buildTimeRangeDesc(entry);

            builder.append("\n").append(key[1])
                    .append(" [").append(key[0]).append("]")
                    .append(timeRangeDesc)
                    .append(", 使用了").append(entry.getValue().size()).append("个").append(key[2]);
        }

        String[] adminIds = faction.getGroupAdminIds().split(",");
        List<AtQqMsg> adminList = Arrays.stream(adminIds).map(s -> new AtQqMsg(Long.parseLong(s))).toList();

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(faction.getGroupId())
                .addMsg(adminList)
                .addMsg(new TextQqMsg(builder.toString()))
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 构建时间范围描述
     */
    private String buildTimeRangeDesc(Map.Entry<String, List<TornFactionItemUsedDO>> entry) {
        TornFactionItemUsedDO earliest = entry.getValue().stream()
                .min(Comparator.comparing(TornFactionItemUsedDO::getUseTime))
                .orElse(null);
        TornFactionItemUsedDO latest = entry.getValue().stream()
                .max(Comparator.comparing(TornFactionItemUsedDO::getUseTime))
                .orElse(null);
        if (earliest == null || latest == null) {
            return "";
        }

        if (earliest.equals(latest)) {
            return " 在" + DateTimeUtils.convertToString(earliest.getUseTime()) + "时";
        } else {
            return " 从" + DateTimeUtils.convertToString(earliest.getUseTime()) +
                    "到" + DateTimeUtils.convertToString(latest.getUseTime()) + "期间";
        }
    }
}