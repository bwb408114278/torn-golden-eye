package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Torn常量
 *
 * @author Bai
 * @version 1.6.4
 * @since 2025.07.22
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornConstants {
    // ====================基础设置相关====================
    /**
     * 基础路径
     */
    public static final String BASE_URL = "https://api.torn.com";
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
    /**
     * BSU帮派ID
     */
    public static final long FACTION_BSU_ID = 11796;
    /**
     * PTA帮派ID
     */
    public static final long FACTION_PTA_ID = 9356;

    // ====================OC相关====================
    public static final String OC_NAME_WINDOW_OF_OPPORTUNITY = "Window of Opportunity";
    public static final String OC_NAME_BLAST_FROM_THE_PAST = "Blast from the Past";
    public static final String OC_NAME_CLINICAL_PRECISION = "Clinical Precision";
    public static final String OC_NAME_BREAK_THE_BANK = "Break the Bank";
    public static final String OC_NAME_STACKING_THE_DECK = "Stacking the Deck";
    public static final String OC_NAME_ACE_IN_THE_HOLE = "Ace in the Hole";
    public static final String OC_NAME_LOCK_STOCK = "Lock Stock";
    public static final String OC_NAME_HOSTILE_TAKEOVER = "Hostile Takeover";
    public static final String OC_NAME_MANIFEST_CRUELTY = "Manifest Cruelty";
    public static final String OC_NAME_GONE_FISSION = "Gone Fission";
    public static final String OC_NAME_CRANE_REACTION = "Crane Reaction";

    // ====================RW相关====================
    /**
     * RW活跃对战窗口长度（分钟）
     */
    public static final int RW_ACTIVE_WINDOW_MINUTES = 3;
    /**
     * RW活跃对战窗口内满足对冲的最小出手次数
     */
    public static final int RW_ACTIVE_MIN_BATTLE_COUNT = 100;

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
     * 飞书多维表 - 师父排队
     */
    public static final String BIT_TABLE_MASTER_QUEUE = "master_queue";
    /**
     * 飞书云文档 - RW对冲
     */
    public static final String TABLE_RW_FIERCE = "rw_fierce";

    /**
     * 用户状态 - 离线
     */
    public static final String USER_STATUS_OFFLINE = "Offline";
    /**
     * 用户状态 - 在线
     */
    public static final String USER_STATUS_ONLINE = "Online";
    /**
     * 用户名称 - 匿名
     */
    public static final String SOMEONE = "Someone";
    /**
     * 订阅备注
     */
    public static final String REMARK_SUBSCRIBE = "golden-eye subscribe";
    /**
     * 订阅校验
     */
    public static final String VALID_SUBSCRIBE = "goldeneyesubscribe";

    public static final List<String> DEFENDER_ATTACK_TYPE = new ArrayList<>();
    public static final List<String> SYRINGE = new ArrayList<>();

    static {
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