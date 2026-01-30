package pn.torn.goldeneye.torn.manager.faction.armory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.TornItemTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帮派物品使用记录公共逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FactionItemUsedManager {
    private final Bot bot;
    private final TornApi tornApi;
    private final TornItemsManager itemsManager;
    private final TornUserManager userManager;
    private final TornFactionItemUsedDAO usedDao;
    private static final List<String> MISUSE_ITEM_LIST = new ArrayList<>();
    private static final Pattern USER_LINK_PATTERN = Pattern.compile(
            "<a href\\s*=\\s*\"http://www\\.torn\\.com/profiles\\.php\\?XID=(\\d+)\">([^<]+)</a>");

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        MISUSE_ITEM_LIST.add("Pixie Sticks");
        MISUSE_ITEM_LIST.add("Bottle of Moonshine");
    }

    /**
     * 爬取物品使用记录
     */
    public void spiderItemUseData(TornSettingFactionDO faction, LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionNewsDTO param;
        TornFactionNewsListVO resp;
        LocalDateTime queryTo = to;
        List<TornFactionItemUsedDO> newsList;
        List<TornFactionItemUsedDO> misuseList = new ArrayList<>();

        do {
            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.ARMORY_ACTION, from, queryTo, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionNewsListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getNews())) {
                break;
            }

            newsList = resp.getNews().stream()
                    .map(n -> convert2DO(faction.getId(), n))
                    .toList();
            List<TornFactionItemUsedDO> dataList = buildDataList(newsList);
            if (!CollectionUtils.isEmpty(dataList)) {
                for (TornFactionItemUsedDO data : dataList) {
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
        } while (resp.getNews().size() >= limit);

        sendWarningMsg(faction, misuseList);
    }

    /**
     * 解析HTML文本，提取物品使用信息
     */
    private TornFactionItemUsedDO convert2DO(long factionId, TornFactionNewsVO news) {
        TornFactionItemUsedDO use = new TornFactionItemUsedDO();
        use.setId(news.getId());
        use.setFactionId(factionId);
        use.setUseTime(DateTimeUtils.convertToDateTime(news.getTimestamp()));

        Matcher linkMatcher = USER_LINK_PATTERN.matcher(news.getText());
        if (linkMatcher.find()) {
            use.setUserId(Long.parseLong(linkMatcher.group(1)));
            String remainingText = news.getText().substring(linkMatcher.end()).trim();
            String[] parts = remainingText.split("\\s+", 2);

            if (parts.length >= 2) {
                use.setUseType(parts[0]);
                for (String itemName : itemsManager.getSortItemNameList()) {
                    if (parts[1].contains(itemName)) {
                        use.setItemName(itemName);
                        break;
                    }
                }
            }
        }

        return use;
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
            String nickname = userManager.getUserMap().get(item.getUserId()).getNickname();
            String key = item.getUserId() + "#" + nickname + "#" + item.getItemName();
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