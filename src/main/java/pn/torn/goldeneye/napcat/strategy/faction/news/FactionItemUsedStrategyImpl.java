package pn.torn.goldeneye.napcat.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.model.faction.armory.ItemUseRankingDO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物资毁灭者策略实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class FactionItemUsedStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornItemsManager itemsManager;
    private final TornFactionItemUsedDAO itemUsedDao;
    private static final Map<String, String> ITEM_ALAIS_MAP = new LinkedHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        ITEM_ALAIS_MAP.put("小红", "Small First Aid Kit");
        ITEM_ALAIS_MAP.put("小蓝", "First Aid Kit");
        ITEM_ALAIS_MAP.put("吗啡", "Morphine");
        ITEM_ALAIS_MAP.put("吐根", "Ipecac Syrup");
        ITEM_ALAIS_MAP.put("空血包", "Empty Blood Bag");
        ITEM_ALAIS_MAP.put("A+血", "Blood Bag : A+");
        ITEM_ALAIS_MAP.put("A-血", "Blood Bag : A-");
        ITEM_ALAIS_MAP.put("B+血", "Blood Bag : B+");
        ITEM_ALAIS_MAP.put("B-血", "Blood Bag : B-");
        ITEM_ALAIS_MAP.put("AB+血", "Blood Bag : AB+");
        ITEM_ALAIS_MAP.put("AB-血", "Blood Bag : AB-");
        ITEM_ALAIS_MAP.put("O+血", "Blood Bag : O+");
        ITEM_ALAIS_MAP.put("O-血", "Blood Bag : O-");
        ITEM_ALAIS_MAP.put("XAN", "Xanax");
        ITEM_ALAIS_MAP.put("啤酒", "Bottle of Beer");
    }

    @Override
    public String getCommand() {
        return BotCommands.FACTION_ITEM_USED;
    }

    @Override
    public String getCommandDescription() {
        return "让我看看谁是帮派蛀虫";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            StringBuilder builder = new StringBuilder();
            ITEM_ALAIS_MAP.keySet().forEach(i -> builder.append(", ").append(i));
            return super.buildTextMsg("请输入物品名称或别名, 例如\ng#" + BotCommands.FACTION_ITEM_USED + "#小红" +
                    "\ng#" + BotCommands.FACTION_ITEM_USED + "#Empty Blood Bag" +
                    "\n\n支持的别名如下: \n" + builder.toString().replaceFirst(", ", ""));
        }

        String itemName = ITEM_ALAIS_MAP.get(msg.toUpperCase());
        if (!StringUtils.hasText(itemName)) {
            TornItemsDO item = itemsManager.getNameMap().get(msg);
            itemName = item == null ? "" : item.getItemName();
        }

        if (!StringUtils.hasText(itemName)) {
            return super.buildTextMsg("未找到物品");
        }

        long factionId = super.getTornFactionIdBySender(sender);
        LocalDateTime toDate = LocalDate.now().atTime(7, 59, 59);
        LocalDateTime fromDate = toDate.minusDays(30).plusSeconds(1);
        List<ItemUseRankingDO> rankingList = itemUsedDao.queryItemUseRanking(factionId, itemName, fromDate, toDate);
        if (CollectionUtils.isEmpty(rankingList)) {
            return super.buildTextMsg("未找到物品使用记录");
        }

        long totalCount = itemUsedDao.lambdaQuery()
                .eq(TornFactionItemUsedDO::getFactionId, factionId)
                .eq(TornFactionItemUsedDO::getItemName, itemName)
                .between(TornFactionItemUsedDO::getUseTime, fromDate, toDate)
                .count();

        String alias = itemName;
        for (Map.Entry<String, String> entry : ITEM_ALAIS_MAP.entrySet()) {
            if (entry.getValue().equals(itemName)) {
                alias = entry.getKey();
                break;
            }
        }

        return super.buildImageMsg(buildRankingMsg(factionId, alias, rankingList, totalCount));
    }

    /**
     * 构建物资毁灭者排行榜表格
     */
    private String buildRankingMsg(long factionId, String itemName, List<ItemUseRankingDO> rankingList, long totalCount) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        tableData.add(List.of(faction.getFactionShortName() + "近30日" + itemName + "毁灭者", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 5);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "数量", "总占比"));
        tableConfig.setSubTitle(1, 5);

        for (int i = 0; i < rankingList.size(); i++) {
            ItemUseRankingDO ranking = rankingList.get(i);
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ranking.getUserId().toString(),
                    ranking.getNickname(),
                    ranking.getTotal().toString(),
                    BigDecimal.valueOf(ranking.getTotal())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP) + "%"));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}