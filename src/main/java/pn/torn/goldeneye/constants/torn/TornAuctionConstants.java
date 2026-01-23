package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * Torn拍卖行常量
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.20
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornAuctionConstants {
    public static final String BONUS_STRICKEN = "Stricken";
    public static final String BONUS_POWERFUL = "Powerful";
    public static final String BONUS_EXPOSE = "Expose";
    public static final String BONUS_DEADEYE = "Deadeye";
    public static final String BONUS_PUNCTURE = "Puncture";
    public static final String BONUS_PENETRATE = "Penetrate";
    public static final Map<String, String> BONUS_ALAIS_MAP = new LinkedHashMap<>();
    public static final Map<String, String> ITEM_ALAIS_MAP = new LinkedHashMap<>();
    public static final Map<String, String> RARITYT_ALAIS_MAP = new HashMap<>();
    public static final Map<String, String> CATEGORY_ALAIS_MAP = new HashMap<>();
    public static final List<String> BONUS_LIST = new ArrayList<>();
    public static final List<String> ITEM_LIST = new ArrayList<>();

    static {
        buildBonusAliasMap();
        buildItemAliasMap();
        buildRarityAliasMap();
        buildCategoryAliasMap();
        buildBonusList();
        buildItemList();
    }

    /**
     * 构建特效别名Map
     */
    private static void buildBonusAliasMap() {
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
    private static void buildItemAliasMap() {
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
        ITEM_ALAIS_MAP.put("R甲", "Riot Body");
        ITEM_ALAIS_MAP.put("R手", "Riot Gloves");
        ITEM_ALAIS_MAP.put("R腿", "Riot Pants");
        ITEM_ALAIS_MAP.put("R脚", "Riot Boots");
        ITEM_ALAIS_MAP.put("A头", "Assault Helmet");
        ITEM_ALAIS_MAP.put("A甲", "Assault Body");
        ITEM_ALAIS_MAP.put("A手", "Assault Gloves");
        ITEM_ALAIS_MAP.put("A腿", "Assault Pants");
        ITEM_ALAIS_MAP.put("A脚", "Assault Boots");
        ITEM_ALAIS_MAP.put("V头", "Vanguard Respirator");
        ITEM_ALAIS_MAP.put("V甲", "Vanguard Body");
        ITEM_ALAIS_MAP.put("V手", "Vanguard Gloves");
        ITEM_ALAIS_MAP.put("V腿", "Vanguard Pants");
        ITEM_ALAIS_MAP.put("V脚", "Vanguard Boots");
        ITEM_ALAIS_MAP.put("先锋头", "Vanguard Respirator");
        ITEM_ALAIS_MAP.put("先锋甲", "Vanguard Body");
        ITEM_ALAIS_MAP.put("先锋手", "Vanguard Gloves");
        ITEM_ALAIS_MAP.put("先锋腿", "Vanguard Pants");
        ITEM_ALAIS_MAP.put("先锋脚", "Vanguard Boots");
        ITEM_ALAIS_MAP.put("血牛头", "Marauder Face Mask");
        ITEM_ALAIS_MAP.put("血牛甲", "Marauder Body");
        ITEM_ALAIS_MAP.put("血牛手", "Marauder Gloves");
        ITEM_ALAIS_MAP.put("血牛腿", "Marauder Pants");
        ITEM_ALAIS_MAP.put("血牛脚", "Marauder Boots");
        ITEM_ALAIS_MAP.put("哨兵头", "Sentinel Helmet");
        ITEM_ALAIS_MAP.put("哨兵甲", "Sentinel Apron");
        ITEM_ALAIS_MAP.put("哨兵手", "Sentinel Gloves");
        ITEM_ALAIS_MAP.put("哨兵腿", "Sentinel Pants");
        ITEM_ALAIS_MAP.put("哨兵脚", "Sentinel Boots");
        ITEM_ALAIS_MAP.put("EOD头", "EOD Helmet");
        ITEM_ALAIS_MAP.put("EOD甲", "EOD Apron");
        ITEM_ALAIS_MAP.put("EOD手", "EOD Gloves");
        ITEM_ALAIS_MAP.put("EOD腿", "EOD Pants");
        ITEM_ALAIS_MAP.put("EOD脚", "EOD Boots");
    }

    /**
     * 构建稀有度别名Map
     */
    private static void buildRarityAliasMap() {
        RARITYT_ALAIS_MAP.put("红", "Red");
        RARITYT_ALAIS_MAP.put("橙", "Orange");
        RARITYT_ALAIS_MAP.put("黄", "Yellow");
    }

    /**
     * 构建武器类型别名Map
     */
    private static void buildCategoryAliasMap() {
        CATEGORY_ALAIS_MAP.put("主手", "Primary");
        CATEGORY_ALAIS_MAP.put("副手", "Secondary");
        CATEGORY_ALAIS_MAP.put("近战", "Melee");
        CATEGORY_ALAIS_MAP.put("肉搏", "Melee");
    }

    /**
     * 构建特效列表
     */
    private static void buildBonusList() {
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
     * 构建物品列表
     */
    private static void buildItemList() {
        ITEM_LIST.add("9mm Uzi");
        ITEM_LIST.add("AK-47");
        ITEM_LIST.add("AK74U");
        ITEM_LIST.add("ArmaLite M-15A4");
        ITEM_LIST.add("Assault Body");
        ITEM_LIST.add("Assault Boots");
        ITEM_LIST.add("Assault Gloves");
        ITEM_LIST.add("Assault Helmet");
        ITEM_LIST.add("Assault Pants");
        ITEM_LIST.add("Axe");
        ITEM_LIST.add("Baseball Bat");
        ITEM_LIST.add("Benelli M1 Tactical");
        ITEM_LIST.add("Benelli M4 Super");
        ITEM_LIST.add("Beretta 92FS");
        ITEM_LIST.add("Beretta M9");
        ITEM_LIST.add("Blunderbuss");
        ITEM_LIST.add("Bo Staff");
        ITEM_LIST.add("Bread Knife");
        ITEM_LIST.add("BT MP9");
        ITEM_LIST.add("Bushmaster Carbon 15");
        ITEM_LIST.add("Butterfly Knife");
        ITEM_LIST.add("Chain Whip");
        ITEM_LIST.add("China Lake");
        ITEM_LIST.add("Claymore Sword");
        ITEM_LIST.add("Cobra Derringer");
        ITEM_LIST.add("Cricket Bat");
        ITEM_LIST.add("Crowbar");
        ITEM_LIST.add("Dagger");
        ITEM_LIST.add("Delta Body");
        ITEM_LIST.add("Delta Boots");
        ITEM_LIST.add("Delta Gas Mask");
        ITEM_LIST.add("Delta Gloves");
        ITEM_LIST.add("Delta Pants");
        ITEM_LIST.add("Desert Eagle");
        ITEM_LIST.add("Diamond Bladed Knife");
        ITEM_LIST.add("Dual Bushmasters");
        ITEM_LIST.add("Dual Uzis");
        ITEM_LIST.add("Dune Boots");
        ITEM_LIST.add("Dune Gloves");
        ITEM_LIST.add("Dune Helmet");
        ITEM_LIST.add("Dune Pants");
        ITEM_LIST.add("Dune Vest");
        ITEM_LIST.add("Enfield SA-80");
        ITEM_LIST.add("EOD Apron");
        ITEM_LIST.add("EOD Boots");
        ITEM_LIST.add("EOD Gloves");
        ITEM_LIST.add("EOD Helmet");
        ITEM_LIST.add("EOD Pants");
        ITEM_LIST.add("Fiveseven");
        ITEM_LIST.add("Flail");
        ITEM_LIST.add("Frying Pan");
        ITEM_LIST.add("Glock 17");
        ITEM_LIST.add("Gold Plated AK-47");
        ITEM_LIST.add("Guandao");
        ITEM_LIST.add("Hammer");
        ITEM_LIST.add("Handbag");
        ITEM_LIST.add("Hazmat Suit");
        ITEM_LIST.add("Heckler & Koch SL8");
        ITEM_LIST.add("Ithaca 37");
        ITEM_LIST.add("Jackhammer");
        ITEM_LIST.add("Kama");
        ITEM_LIST.add("Katana");
        ITEM_LIST.add("Kitchen Knife");
        ITEM_LIST.add("Knuckle Dusters");
        ITEM_LIST.add("Kodachi");
        ITEM_LIST.add("Leather Bullwhip");
        ITEM_LIST.add("Lorcin 380");
        ITEM_LIST.add("Luger");
        ITEM_LIST.add("M16 A2 Rifle");
        ITEM_LIST.add("M249 SAW");
        ITEM_LIST.add("M4A1 Colt Carbine");
        ITEM_LIST.add("Macana");
        ITEM_LIST.add("Mag 7");
        ITEM_LIST.add("Magnum");
        ITEM_LIST.add("M'aol Hooves");
        ITEM_LIST.add("M'aol Visage");
        ITEM_LIST.add("Marauder Body");
        ITEM_LIST.add("Marauder Boots");
        ITEM_LIST.add("Marauder Face Mask");
        ITEM_LIST.add("Marauder Gloves");
        ITEM_LIST.add("Marauder Pants");
        ITEM_LIST.add("Metal Nunchaku");
        ITEM_LIST.add("Milkor MGL");
        ITEM_LIST.add("Minigun");
        ITEM_LIST.add("MP 40");
        ITEM_LIST.add("MP5k");
        ITEM_LIST.add("MP5 Navy");
        ITEM_LIST.add("Naval Cutlass");
        ITEM_LIST.add("Negev NG-5");
        ITEM_LIST.add("Ninja Claws");
        ITEM_LIST.add("Nock Gun");
        ITEM_LIST.add("P90");
        ITEM_LIST.add("Pen Knife");
        ITEM_LIST.add("PKM");
        ITEM_LIST.add("Poison Umbrella");
        ITEM_LIST.add("Qsz-92");
        ITEM_LIST.add("Raven MP25");
        ITEM_LIST.add("Rheinmetall MG 3");
        ITEM_LIST.add("Riot Body");
        ITEM_LIST.add("Riot Boots");
        ITEM_LIST.add("Riot Gloves");
        ITEM_LIST.add("Riot Helmet");
        ITEM_LIST.add("Riot Pants");
        ITEM_LIST.add("RPG Launcher");
        ITEM_LIST.add("Ruger 57");
        ITEM_LIST.add("Sai");
        ITEM_LIST.add("Samurai Sword");
        ITEM_LIST.add("Sawed-Off Shotgun");
        ITEM_LIST.add("Scimitar");
        ITEM_LIST.add("Sentinel Apron");
        ITEM_LIST.add("Sentinel Boots");
        ITEM_LIST.add("Sentinel Gloves");
        ITEM_LIST.add("Sentinel Helmet");
        ITEM_LIST.add("Sentinel Pants");
        ITEM_LIST.add("SIG 552");
        ITEM_LIST.add("Skorpion");
        ITEM_LIST.add("SKS Carbine");
        ITEM_LIST.add("Sledgehammer");
        ITEM_LIST.add("SMAW Launcher");
        ITEM_LIST.add("Snow Cannon");
        ITEM_LIST.add("Spear");
        ITEM_LIST.add("Springfield 1911");
        ITEM_LIST.add("Steyr AUG");
        ITEM_LIST.add("Stoner 96");
        ITEM_LIST.add("Swiss Army Knife");
        ITEM_LIST.add("S&W Revolver");
        ITEM_LIST.add("Taurus");
        ITEM_LIST.add("Tavor TAR-21");
        ITEM_LIST.add("Thompson");
        ITEM_LIST.add("TMP");
        ITEM_LIST.add("Type 98 Anti Tank");
        ITEM_LIST.add("USP");
        ITEM_LIST.add("Vanguard Body");
        ITEM_LIST.add("Vanguard Boots");
        ITEM_LIST.add("Vanguard Gloves");
        ITEM_LIST.add("Vanguard Pants");
        ITEM_LIST.add("Vanguard Respirator");
        ITEM_LIST.add("Vektor CR-21");
        ITEM_LIST.add("Wooden Nunchaku");
        ITEM_LIST.add("XM8 Rifle");
        ITEM_LIST.add("Yasukuni Sword");
    }
}