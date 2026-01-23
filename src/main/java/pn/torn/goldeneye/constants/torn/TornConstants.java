package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Torn常量
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.07.22
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornConstants {
    // ====================基础设置相关====================
    /**
     * 基础路径
     */
    public static final String BASE_URL_V2 = "https://api.torn.com/v2";
    /**
     * PN帮派ID
     */
    public static final long FACTION_PN_ID = 20465L;
    /**
     * HP帮派ID
     */
    public static final long FACTION_HP_ID = 2095L;
    /**
     * CC帮派ID
     */
    public static final long FACTION_CCRC_ID = 27902L;
    /**
     * SH帮派ID
     */
    public static final long FACTION_SH_ID = 36134L;
    /**
     * Nov帮派ID
     */
    public static final long FACTION_NOV_ID = 16335;

    // ====================OC相关====================
    public static final List<String> ROTATION_OC_NAME = new ArrayList<>();
    public static final List<Long> REASSIGN_OC_FACTION = new ArrayList<>();

    // ====================物品相关====================
    public static final String ITEM_TYPE_WEAPON = "Weapon";

    // ====================飞书相关====================
    /**
     * 飞书多维表 - OC收益
     */
    public static final String BIT_TABLE_OC_BENEFIT = "oc_benefit";
    /**
     * 飞书多维表 - 拍卖行
     */
    public static final String BIT_TABLE_AUCTION = "auction";

    /**
     * 用户状态 - 离线
     */
    public static final String USER_STATUS_OFFLINE = "Offline";
    /**
     * 用户名称 - 匿名
     */
    public static final String SOMEONE = "Someone";



    public static final List<String> DEFENDER_ATTACK_TYPE = new ArrayList<>();
    public static final List<String> SYRINGE = new ArrayList<>();

    static {
        ROTATION_OC_NAME.add("Blast from the Past");
        ROTATION_OC_NAME.add("Break the Bank");
        ROTATION_OC_NAME.add("Clinical Precision");

        REASSIGN_OC_FACTION.add(FACTION_PN_ID);
        REASSIGN_OC_FACTION.add(FACTION_HP_ID);
        REASSIGN_OC_FACTION.add(FACTION_CCRC_ID);
        REASSIGN_OC_FACTION.add(FACTION_SH_ID);
        REASSIGN_OC_FACTION.add(FACTION_NOV_ID);

        DEFENDER_ATTACK_TYPE.add("lost to");
        DEFENDER_ATTACK_TYPE.add("began bleeding");
        DEFENDER_ATTACK_TYPE.add("is poisoned");
        DEFENDER_ATTACK_TYPE.add("is eviscerated");
        DEFENDER_ATTACK_TYPE.add("is weakened");
        DEFENDER_ATTACK_TYPE.add("is shocked");
        DEFENDER_ATTACK_TYPE.add("is withered");
        DEFENDER_ATTACK_TYPE.add("is crippled");

        SYRINGE.add("Serotonin");
        SYRINGE.add("Tyrosine");
        SYRINGE.add("Melatonin");
        SYRINGE.add("Epinephrine");
    }
}