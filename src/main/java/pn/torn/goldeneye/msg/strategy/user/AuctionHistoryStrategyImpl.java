package pn.torn.goldeneye.msg.strategy.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornAuctionDAO;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.utils.CharacterUtils;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static pn.torn.goldeneye.constants.torn.TornAuctionConstants.*;

/**
 * 拍卖行历史策略实现类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.15
 */
@Component
@RequiredArgsConstructor
public class AuctionHistoryStrategyImpl extends SmthMsgStrategy {
    private final TornItemsManager itemsManager;
    private final TornAuctionDAO auctionDao;

    @Override
    public String getCommand() {
        return BotCommands.AUCTION_HISTORY;
    }

    @Override
    public String getCommandDescription() {
        return "查询RW装备拍卖成交历史";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = msg.split("#");
        String[] bonusArray = msgArray.length > 0 ? msgArray[0].split(":") : new String[1];

        List<String> bonus = buildBonusCondition(bonusArray[0]);
        List<String> item = msgArray.length > 2 ? buildItemCondition(msgArray[2]) : List.of();
        String category = msgArray.length > 2 ? buildCategoryCondition(msgArray[2]) : "";
        if (CollectionUtils.isEmpty(bonus) && CollectionUtils.isEmpty(item) && !StringUtils.hasText(category)) {
            return super.buildTextMsg("特效和物品至少要有一个");
        }

        int bonusValue = checkBonusValue(bonusArray);
        String rarity = msgArray.length > 1 ? buildRarityCondition(msgArray[1]) : "";

        Page<TornAuctionDO> auctionPage = auctionDao.lambdaQuery()
                .and(!bonus.isEmpty() || bonusValue > 0, wrapper -> wrapper.or(
                                w -> w.in(!bonus.isEmpty(), TornAuctionDO::getBonus1Title, bonus)
                                        .and(bonusValue > 0, w2 ->
                                                w2.ge(TornAuctionDO::getBonus1Value, bonusValue)))
                        .or(w -> w.in(!bonus.isEmpty(), TornAuctionDO::getBonus2Title, bonus)
                                .and(bonusValue > 0, w2 ->
                                        w2.ge(TornAuctionDO::getBonus2Value, bonusValue))))
                .in(!item.isEmpty(), TornAuctionDO::getItemName, item)
                .eq(StringUtils.hasText(rarity), TornAuctionDO::getItemRarity, rarity)
                .eq(StringUtils.hasText(category), TornAuctionDO::getWeaponCategory, category)
                .orderByDesc(TornAuctionDO::getFinishTime)
                .page(new Page<>(1, 30));
        if (CollectionUtils.isEmpty(auctionPage.getRecords())) {
            return super.buildTextMsg("未找到拍卖历史记录");
        }

        return super.buildImageMsg(buildTableMsg(auctionPage.getRecords(),
                new TableTitleConfig(bonus, bonusValue, item, rarity, category)));
    }

    /**
     * 构建表格消息
     */
    private String buildTableMsg(List<TornAuctionDO> auctionList, TableTitleConfig titleConfig) {
        TornAuctionDO sample = auctionList.getFirst();
        boolean isWeapon = TornConstants.ITEM_TYPE_WEAPON.equals(sample.getItemType());

        List<TableColumnConfig> columns = new ArrayList<>();
        columns.add(new TableColumnConfig("名称", TornAuctionDO::getItemName));
        columns.add(new TableColumnConfig("成交价格", a -> NumberUtils.addDelimiters(a.getPrice())));
        columns.add(new TableColumnConfig("成交时间", a -> DateTimeUtils.convertToString(a.getFinishTime())));
        columns.add(new TableColumnConfig("品质", a -> a.getItemQuality().toString()));
        columns.add(new TableColumnConfig("稀有度", TornAuctionDO::getItemRarity));

        if (isWeapon) {
            columns.add(new TableColumnConfig("伤害", a -> a.getItemDamage().toString()));
            columns.add(new TableColumnConfig("命中", a -> a.getItemAccuracy().toString()));
        } else {
            columns.add(new TableColumnConfig("护甲", a -> a.getItemArmor().toString()));
        }

        columns.add(new TableColumnConfig("特效", a ->
                a.getBonus1Value() + "% " + a.getBonus1Title() +
                        (StringUtils.hasText(a.getBonus2Title()) ?
                                ", " + a.getBonus2Value() + "% " + a.getBonus2Title() : "")));

        return buildTable(auctionList, titleConfig, columns);
    }

    /**
     * 构建表格消息
     */
    private String buildTable(List<TornAuctionDO> auctionList,
                              TableTitleConfig titleConfig, List<TableColumnConfig> columns) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        // 标题行
        int columnCount = columns.size();
        List<String> titleRow = new ArrayList<>(Collections.nCopies(columnCount, ""));
        String title = (titleConfig.rarity().isEmpty() ? "" : titleConfig.rarity()) +
                (titleConfig.bonus().isEmpty() ? "" : (String.join("/", titleConfig.bonus()))) +
                (titleConfig.bonusValue() < 1 ? "" : "(" + titleConfig.bonusValue() + "以上)") +
                (titleConfig.itemName().isEmpty() ? "" : " " + String.join("/", titleConfig.itemName())) +
                (titleConfig.category().isEmpty() ? "" : titleConfig.category());
        titleRow.set(0, "最近" + title + "的拍卖成交记录");
        tableData.add(titleRow);

        tableConfig.addMerge(0, 0, 1, columnCount);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));
        // 表头行
        List<String> headerRow = columns.stream()
                .map(TableColumnConfig::header)
                .toList();
        tableData.add(headerRow);
        tableConfig.setSubTitle(1, columnCount);
        // 数据行
        for (TornAuctionDO auction : auctionList) {
            List<String> row = columns.stream()
                    .map(col -> col.getValue(auction))
                    .toList();
            tableData.add(row);
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 构建格式介绍消息
     */
    private String buildFormatIntroMsg() {
        StringBuilder bonusBuilder = new StringBuilder();
        BONUS_ALAIS_MAP.keySet().forEach(i -> bonusBuilder.append(", ").append(i));

        StringBuilder itemBuilder = new StringBuilder();
        ITEM_ALAIS_MAP.keySet().forEach(i -> itemBuilder.append(", ").append(i));

        return "查询格式: g#特效:值#稀有度#位置, 举例如下: " +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#Revitalize, 查询所有的回E武器" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#回E, 查询所有的回E武器" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#回E:15, 查询15以上回E武器" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#回E#橙, 查询所有橙色回E武器" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#回E##副手, 查询所有回E副手" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#emp##kod, 查询所有Empower的Kodachi" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "###EOD头, 查询所有EOD头" +
                "\ng#" + BotCommands.AUCTION_HISTORY + "#:22##EOD头, 查询特效在22以上的EOD头" +
                "\n\n支持的特效别名如下: \n" + bonusBuilder.toString().replaceFirst(", ", "") + ", 穿甲, 破甲" +
                "\n支持的物品别名如下, 全名时左起字母匹配可模糊查询: \n" + itemBuilder.toString().replaceFirst(", ", "") + ", 日本刀";
    }

    /**
     * 检查bonus值
     */
    private int checkBonusValue(String[] bonus) {
        if (bonus.length > 1) {
            if (!NumberUtils.isInt(bonus[1])) {
                throw new BizException("特效数值必须为数字");
            }

            return Integer.parseInt(bonus[1]);
        }

        return 0;
    }

    /**
     * 构建加成条件列表
     */
    private List<String> buildBonusCondition(String msg) {
        if (!StringUtils.hasText(msg)) {
            return List.of();
        }

        String bonusParam = msg.toUpperCase().replace(" ", "");
        for (Map.Entry<String, String> entry : BONUS_ALAIS_MAP.entrySet()) {
            if (entry.getKey().toUpperCase().replace(" ", "").equals(bonusParam)) {
                return List.of(entry.getValue());
            }
        }

        if ("穿甲".equals(msg) || "破甲".equals(msg)) {
            return List.of(BONUS_PUNCTURE, BONUS_PENETRATE);
        }

        List<String> resultList = new ArrayList<>();
        for (String bonus : BONUS_LIST) {
            if (bonus.toUpperCase().replace(" ", "").startsWith(bonusParam)) {
                resultList.add(bonus);
            }
        }

        if (resultList.isEmpty()) {
            throw new BizException("未识别的特效");
        }
        return resultList;
    }

    /**
     * 构建物品条件列表
     */
    private List<String> buildItemCondition(String msg) {
        if (!StringUtils.hasText(msg)) {
            return List.of();
        }

        String itemParam = msg.toUpperCase().replace(" ", "");
        for (Map.Entry<String, String> entry : ITEM_ALAIS_MAP.entrySet()) {
            if (entry.getKey().toUpperCase().replace(" ", "").equals(itemParam)) {
                return List.of(entry.getValue());
            }
        }

        if ("日本刀".equals(msg)) {
            return List.of("Kodachi", "Katana", "Yasukuni Sword", "Samurai Sword");
        }

        List<String> resultList = new ArrayList<>();
        for (String itemName : ITEM_LIST) {
            if (itemName.toUpperCase().replace(" ", "").startsWith(itemParam)) {
                resultList.add(itemName);
            }
        }

        return resultList;
    }

    /**
     * 构建武器类型条件
     */
    private String buildCategoryCondition(String msg) {
        if (!StringUtils.hasText(msg)) {
            return "";
        }

        if (CATEGORY_ALAIS_MAP.containsKey(msg)) {
            return CATEGORY_ALAIS_MAP.get(msg);
        }

        String categoryParam = msg.toUpperCase();
        if ("PRIMARY".equals(categoryParam) || "SECONDARY".equals(categoryParam) || "MELEE".equals(categoryParam)) {
            return CharacterUtils.capitalFirstLetter(msg);
        }

        return "";
    }

    /**
     * 构建稀有度条件
     */
    private String buildRarityCondition(String msg) {
        if (!StringUtils.hasText(msg)) {
            return "";
        }

        if (RARITYT_ALAIS_MAP.containsKey(msg)) {
            return RARITYT_ALAIS_MAP.get(msg);
        }

        String rarityParam = msg.toUpperCase();
        if ("RED".equals(rarityParam) || "ORANGE".equals(rarityParam) || "YELLOW".equals(rarityParam)) {
            return CharacterUtils.capitalFirstLetter(msg);
        }

        throw new BizException("未识别的稀有度");
    }

    /**
     * 列配置
     */
    private record TableColumnConfig(String header, Function<TornAuctionDO, String> extractor) {
        public String getValue(TornAuctionDO auction) {
            return extractor.apply(auction);
        }
    }

    /**
     * 表头配置
     */
    private record TableTitleConfig(List<String> bonus, int bonusValue, List<String> itemName,
                                    String rarity, String category) {
        public String rarity() {
            return switch (rarity) {
                case "Red" -> "红";
                case "Orange" -> "橙";
                case "Yellow" -> "黄";
                default -> "";
            };
        }
    }
}