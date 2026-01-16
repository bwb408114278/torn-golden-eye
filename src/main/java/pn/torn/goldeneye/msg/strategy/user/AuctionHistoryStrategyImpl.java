package pn.torn.goldeneye.msg.strategy.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.utils.CharacterUtils;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;

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
    private static final Map<String, String> BONUS_ALAIS_MAP = new LinkedHashMap<>();
    private static final Map<String, String> ITEM_ALAIS_MAP = new LinkedHashMap<>();
    private static final Map<String, String> RARITYT_ALAIS_MAP = new HashMap<>();
    private static final Map<String, String> CATEGORY_ALAIS_MAP = new HashMap<>();
    private static final List<String> BONUS_LIST = new ArrayList<>();
    private static final String BONUS_STRICKEN = "Stricken";
    private static final String BONUS_POWERFUL = "Powerful";
    private static final String BONUS_EXPOSE = "Expose";
    private static final String BONUS_DEADEYE = "Deadeye";
    private static final String BONUS_PUNCTURE = "Puncture";
    private static final String BONUS_PENETRATE = "Penetrate";

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        buildBonusAliasMap();
        buildItemAliasMap();
        buildRarityAliasMap();
        buildCategoryAliasMap();
        buildBonusList();
    }

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

        String bonusRealName = BONUS_LIST.stream()
                .filter(b -> b.toUpperCase().replace(" ", "").equals(bonusParam))
                .findAny().orElse(null);
        if (StringUtils.hasText(bonusRealName)) {
            return List.of(bonusRealName);
        }

        throw new BizException("未识别的特效");
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

        for (Map.Entry<String, TornItemsDO> entry : itemsManager.getNameMap().entrySet()) {
            if (entry.getKey().toUpperCase().replace(" ", "").startsWith(itemParam)) {
                return List.of(entry.getValue().getItemName());
            }
        }

        return List.of();
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
     * 构建特效别名Map
     */
    private void buildBonusAliasMap() {
        BONUS_ALAIS_MAP.put("流血", "Bleed");
        BONUS_ALAIS_MAP.put("节弹", "Conserve");
        BONUS_ALAIS_MAP.put("死眼", BONUS_DEADEYE);
        BONUS_ALAIS_MAP.put("爆伤", BONUS_DEADEYE);
        BONUS_ALAIS_MAP.put("暴伤", BONUS_DEADEYE);
        BONUS_ALAIS_MAP.put("缴械", "Disarm");
        BONUS_ALAIS_MAP.put("Emp", "Empower");
        BONUS_ALAIS_MAP.put("斩杀", "Execute");
        BONUS_ALAIS_MAP.put("暴击", BONUS_EXPOSE);
        BONUS_ALAIS_MAP.put("爆击", BONUS_EXPOSE);
        BONUS_ALAIS_MAP.put("经验", "Proficience");
        BONUS_ALAIS_MAP.put("格挡", "Parry");
        BONUS_ALAIS_MAP.put("Pen", BONUS_PENETRATE);
        BONUS_ALAIS_MAP.put("Mug", "Plunder");
        BONUS_ALAIS_MAP.put("Power", BONUS_POWERFUL);
        BONUS_ALAIS_MAP.put("力量", BONUS_POWERFUL);
        BONUS_ALAIS_MAP.put("Pun", BONUS_PUNCTURE);
        BONUS_ALAIS_MAP.put("回E", "Revitalize");
        BONUS_ALAIS_MAP.put("Spec", "Specialist");
        BONUS_ALAIS_MAP.put("Hos", BONUS_STRICKEN);
        BONUS_ALAIS_MAP.put("Hosp", BONUS_STRICKEN);
        BONUS_ALAIS_MAP.put("面子", "Warlord");
    }

    /**
     * 构建物品别名Map
     */
    private void buildItemAliasMap() {
        ITEM_ALAIS_MAP.put("Uzi", "9mm Uzi");
        ITEM_ALAIS_MAP.put("Arma", "ArmaLite M-15A4");
        ITEM_ALAIS_MAP.put("阿玛", "ArmaLite M-15A4");
        ITEM_ALAIS_MAP.put("MP9", "BT MP9");
        ITEM_ALAIS_MAP.put("中国湖", "China Lake");
        ITEM_ALAIS_MAP.put("Cobra", "Cobra Derringer");
        ITEM_ALAIS_MAP.put("钻石刀", "Diamond Bladed Knife");
        ITEM_ALAIS_MAP.put("DBK", "Diamond Bladed Knife");
        ITEM_ALAIS_MAP.put("SA", "Enfield SA-80");
        ITEM_ALAIS_MAP.put("气锤", "Jackhammer");
        ITEM_ALAIS_MAP.put("RPG", "RPG Launcher");
        ITEM_ALAIS_MAP.put("火箭炮", "RPG Launcher");
        ITEM_ALAIS_MAP.put("SIG", "SIG 552");
        ITEM_ALAIS_MAP.put("TAR", "Tavor TAR-21");
        ITEM_ALAIS_MAP.put("海军刀", "Naval Cutlass");
        ITEM_ALAIS_MAP.put("双节棍", "Metal Nunchaku");
        ITEM_ALAIS_MAP.put("双截棍", "Metal Nunchaku");
        ITEM_ALAIS_MAP.put("R头", "Riot Helmet");
        ITEM_ALAIS_MAP.put("R身", "Riot Body");
        ITEM_ALAIS_MAP.put("R手", "Riot Gloves");
        ITEM_ALAIS_MAP.put("R腿", "Riot Pants");
        ITEM_ALAIS_MAP.put("R脚", "Riot Boots");
        ITEM_ALAIS_MAP.put("A头", "Assault Helmet");
        ITEM_ALAIS_MAP.put("A身", "Assault Body");
        ITEM_ALAIS_MAP.put("A手", "Assault Gloves");
        ITEM_ALAIS_MAP.put("A腿", "Assault Pants");
        ITEM_ALAIS_MAP.put("A脚", "Assault Boots");
        ITEM_ALAIS_MAP.put("V头", "Vanguard Respirator");
        ITEM_ALAIS_MAP.put("V身", "Vanguard Body");
        ITEM_ALAIS_MAP.put("V手", "Vanguard Gloves");
        ITEM_ALAIS_MAP.put("V腿", "Vanguard Pants");
        ITEM_ALAIS_MAP.put("V脚", "Vanguard Boots");
        ITEM_ALAIS_MAP.put("先锋头", "Vanguard Respirator");
        ITEM_ALAIS_MAP.put("先锋身", "Vanguard Body");
        ITEM_ALAIS_MAP.put("先锋手", "Vanguard Gloves");
        ITEM_ALAIS_MAP.put("先锋腿", "Vanguard Pants");
        ITEM_ALAIS_MAP.put("先锋脚", "Vanguard Boots");
        ITEM_ALAIS_MAP.put("血牛头", "Marauder Face Mask");
        ITEM_ALAIS_MAP.put("血牛身", "Marauder Body");
        ITEM_ALAIS_MAP.put("血牛手", "Marauder Gloves");
        ITEM_ALAIS_MAP.put("血牛腿", "Marauder Pants");
        ITEM_ALAIS_MAP.put("血牛脚", "Marauder Boots");
        ITEM_ALAIS_MAP.put("哨兵头", "Sentinel Helmet");
        ITEM_ALAIS_MAP.put("哨兵身", "Sentinel Apron");
        ITEM_ALAIS_MAP.put("哨兵手", "Sentinel Gloves");
        ITEM_ALAIS_MAP.put("哨兵腿", "Sentinel Pants");
        ITEM_ALAIS_MAP.put("哨兵脚", "Sentinel Boots");
        ITEM_ALAIS_MAP.put("EOD头", "EOD Helmet");
        ITEM_ALAIS_MAP.put("EOD身", "EOD Apron");
        ITEM_ALAIS_MAP.put("EOD手", "EOD Gloves");
        ITEM_ALAIS_MAP.put("EOD腿", "EOD Pants");
        ITEM_ALAIS_MAP.put("EOD脚", "EOD Boots");
    }

    /**
     * 构建稀有度别名Map
     */
    private void buildRarityAliasMap() {
        RARITYT_ALAIS_MAP.put("红", "Red");
        RARITYT_ALAIS_MAP.put("橙", "Orange");
        RARITYT_ALAIS_MAP.put("黄", "Yellow");
    }

    /**
     * 构建武器类型别名Map
     */
    private void buildCategoryAliasMap() {
        CATEGORY_ALAIS_MAP.put("主手", "Primary");
        CATEGORY_ALAIS_MAP.put("副手", "Secondary");
        CATEGORY_ALAIS_MAP.put("近战", "Melee");
        CATEGORY_ALAIS_MAP.put("肉搏", "Melee");
    }

    /**
     * 构建特效列表
     */
    private void buildBonusList() {
        BONUS_LIST.add("Achilles");
        BONUS_LIST.add("Assassinate");
        BONUS_LIST.add("Backstab");
        BONUS_LIST.add("Berserk");
        BONUS_LIST.add("Bleed");
        BONUS_LIST.add("Blindfire");
        BONUS_LIST.add("Blindside");
        BONUS_LIST.add("Bloodlust");
        BONUS_LIST.add("Comeback");
        BONUS_LIST.add("Conserve");
        BONUS_LIST.add("Cripple");
        BONUS_LIST.add("Crusher");
        BONUS_LIST.add("Cupid");
        BONUS_LIST.add(BONUS_DEADEYE);
        BONUS_LIST.add("Deadly");
        BONUS_LIST.add("Demoralize");
        BONUS_LIST.add("Disarm");
        BONUS_LIST.add("Double-edged");
        BONUS_LIST.add("Double Tap");
        BONUS_LIST.add("Empower");
        BONUS_LIST.add("Eviscerate");
        BONUS_LIST.add("Execute");
        BONUS_LIST.add(BONUS_EXPOSE);
        BONUS_LIST.add("Finale");
        BONUS_LIST.add("Focus");
        BONUS_LIST.add("Freeze");
        BONUS_LIST.add("Frenzy");
        BONUS_LIST.add("Fury");
        BONUS_LIST.add("Grace");
        BONUS_LIST.add("Hazardous");
        BONUS_LIST.add("Home run");
        BONUS_LIST.add("Immutable");
        BONUS_LIST.add("Impassable");
        BONUS_LIST.add("Impenetrable");
        BONUS_LIST.add("Imperviable");
        BONUS_LIST.add("Impregnable");
        BONUS_LIST.add("Insurmountable");
        BONUS_LIST.add("Invulnerable");
        BONUS_LIST.add("Irradiate");
        BONUS_LIST.add("Irrepressible");
        BONUS_LIST.add("Kinetokinesis");
        BONUS_LIST.add("Lacerate");
        BONUS_LIST.add("Motivation");
        BONUS_LIST.add("Paralyze");
        BONUS_LIST.add("Parry");
        BONUS_LIST.add(BONUS_PENETRATE);
        BONUS_LIST.add("Plunder");
        BONUS_LIST.add(BONUS_POWERFUL);
        BONUS_LIST.add("Proficience");
        BONUS_LIST.add(BONUS_PUNCTURE);
        BONUS_LIST.add("Quicken");
        BONUS_LIST.add("Radiation Protection");
        BONUS_LIST.add("Rage");
        BONUS_LIST.add("Revitalize");
        BONUS_LIST.add("Roshambo");
        BONUS_LIST.add("Slow");
        BONUS_LIST.add("Smash");
        BONUS_LIST.add("Smurf");
        BONUS_LIST.add("Specialist");
        BONUS_LIST.add("Spray");
        BONUS_LIST.add("Storage");
        BONUS_LIST.add(BONUS_STRICKEN);
        BONUS_LIST.add("Stun");
        BONUS_LIST.add("Suppress");
        BONUS_LIST.add("Sure Shot");
        BONUS_LIST.add("Throttle");
        BONUS_LIST.add("Toxin");
        BONUS_LIST.add("Warlord");
        BONUS_LIST.add("Weaken");
        BONUS_LIST.add("Wind-up");
        BONUS_LIST.add("Wither");
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