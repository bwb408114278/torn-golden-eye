package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.model.faction.crime.income.FactionOcExclusion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Torn常量
 *
 * @author Bai
 * @version 1.2.12
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
    /**
     * Key为帮派ID，值为该帮派当前大锅饭OC名单。
     *
     * <p>该名单同时用于OC推荐与分配、大锅饭批量收益候选名称过滤，以及排行榜普通收益排除规则的构造。
     * 对于普通收益排除，不能把完整名单直接解释为所有历史月份都排除，新增范围必须结合生效时间判断，
     * 具体规则见{@link #OC_BENEFIT_EXCLUSION_RULES}。</p>
     */
    public static final Map<Long, List<String>> ROTATION_OC_NAME = new HashMap<>();
    /**
     * 大锅饭普通收益排除规则，Key为帮派ID，值为该帮派的扁平排除规则列表。
     *
     * <p>规则中{@code effectiveFrom}为{@code null}表示该名单在任何历史月份都排除普通收益（原有大锅饭名单）；
     * 非{@code null}表示仅当OC完成时间达到该时间点后才从普通收益数据源排除（本次新增名单）。</p>
     */
    public static final Map<Long, List<FactionOcExclusion>> OC_BENEFIT_EXCLUSION_RULES = new HashMap<>();
    public static final List<Long> REASSIGN_OC_FACTION = new ArrayList<>();

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

    /**
     * PN原有大锅饭OC名单，历史所有月份均从普通收益排除。
     */
    public static final List<String> PN_ORIGINAL_ROTATION_OC_NAME = List.of(
            OC_NAME_ACE_IN_THE_HOLE, OC_NAME_STACKING_THE_DECK, OC_NAME_BREAK_THE_BANK,
            OC_NAME_CLINICAL_PRECISION, OC_NAME_BLAST_FROM_THE_PAST, OC_NAME_WINDOW_OF_OPPORTUNITY);
    /**
     * NOV原有大锅饭OC名单，历史所有月份均从普通收益排除。
     */
    public static final List<String> NOV_ORIGINAL_ROTATION_OC_NAME = List.of(
            OC_NAME_BREAK_THE_BANK, OC_NAME_CLINICAL_PRECISION,
            OC_NAME_BLAST_FROM_THE_PAST, OC_NAME_WINDOW_OF_OPPORTUNITY);
    /**
     * PN本次新增大锅饭OC名单，仅从{@link #PN_OC_REASSIGN_EFFECTIVE_FROM}起从普通收益排除。
     */
    public static final List<String> PN_ADDED_ROTATION_OC_NAME = List.of(
            OC_NAME_LOCK_STOCK, OC_NAME_HOSTILE_TAKEOVER);
    /**
     * NOV本次新增大锅饭OC名单，仅从{@link #NOV_OC_REASSIGN_EFFECTIVE_FROM}起从普通收益排除。
     */
    public static final List<String> NOV_ADDED_ROTATION_OC_NAME = List.of(
            OC_NAME_LOCK_STOCK, OC_NAME_STACKING_THE_DECK, OC_NAME_MANIFEST_CRUELTY,
            OC_NAME_GONE_FISSION, OC_NAME_ACE_IN_THE_HOLE, OC_NAME_HOSTILE_TAKEOVER, OC_NAME_CRANE_REACTION);

    /**
     * PN大锅饭新增名单生效时间，左闭区间。
     */
    public static final LocalDateTime PN_OC_REASSIGN_EFFECTIVE_FROM = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
    /**
     * NOV大锅饭新增名单生效时间，左闭区间。
     */
    public static final LocalDateTime NOV_OC_REASSIGN_EFFECTIVE_FROM = LocalDateTime.of(2026, 7, 1, 0, 0, 0);

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
        ROTATION_OC_NAME.put(FACTION_PN_ID, combine(PN_ORIGINAL_ROTATION_OC_NAME, PN_ADDED_ROTATION_OC_NAME));
        ROTATION_OC_NAME.put(FACTION_HP_ID, List.of(OC_NAME_BREAK_THE_BANK, OC_NAME_CLINICAL_PRECISION,
                OC_NAME_BLAST_FROM_THE_PAST, OC_NAME_WINDOW_OF_OPPORTUNITY));
        ROTATION_OC_NAME.put(FACTION_CCRC_ID, List.of(OC_NAME_BREAK_THE_BANK, OC_NAME_CLINICAL_PRECISION,
                OC_NAME_BLAST_FROM_THE_PAST, OC_NAME_WINDOW_OF_OPPORTUNITY));
        ROTATION_OC_NAME.put(FACTION_SH_ID, List.of(OC_NAME_BREAK_THE_BANK, OC_NAME_CLINICAL_PRECISION,
                OC_NAME_BLAST_FROM_THE_PAST, OC_NAME_WINDOW_OF_OPPORTUNITY));
        ROTATION_OC_NAME.put(FACTION_NOV_ID, combine(NOV_ORIGINAL_ROTATION_OC_NAME, NOV_ADDED_ROTATION_OC_NAME));
        ROTATION_OC_NAME.put(FACTION_BSU_ID, List.of(OC_NAME_BREAK_THE_BANK, OC_NAME_CLINICAL_PRECISION,
                OC_NAME_BLAST_FROM_THE_PAST, OC_NAME_WINDOW_OF_OPPORTUNITY));

        OC_BENEFIT_EXCLUSION_RULES.put(FACTION_PN_ID, List.of(
                new FactionOcExclusion(FACTION_PN_ID, PN_ORIGINAL_ROTATION_OC_NAME, null),
                new FactionOcExclusion(FACTION_PN_ID, PN_ADDED_ROTATION_OC_NAME, PN_OC_REASSIGN_EFFECTIVE_FROM)));
        OC_BENEFIT_EXCLUSION_RULES.put(FACTION_NOV_ID, List.of(
                new FactionOcExclusion(FACTION_NOV_ID, NOV_ORIGINAL_ROTATION_OC_NAME, null),
                new FactionOcExclusion(FACTION_NOV_ID, NOV_ADDED_ROTATION_OC_NAME, NOV_OC_REASSIGN_EFFECTIVE_FROM)));
        OC_BENEFIT_EXCLUSION_RULES.put(FACTION_HP_ID, List.of(
                new FactionOcExclusion(FACTION_HP_ID, ROTATION_OC_NAME.get(FACTION_HP_ID), null)));
        OC_BENEFIT_EXCLUSION_RULES.put(FACTION_CCRC_ID, List.of(
                new FactionOcExclusion(FACTION_CCRC_ID, ROTATION_OC_NAME.get(FACTION_CCRC_ID), null)));
        OC_BENEFIT_EXCLUSION_RULES.put(FACTION_SH_ID, List.of(
                new FactionOcExclusion(FACTION_SH_ID, ROTATION_OC_NAME.get(FACTION_SH_ID), null)));
        OC_BENEFIT_EXCLUSION_RULES.put(FACTION_BSU_ID, List.of(
                new FactionOcExclusion(FACTION_BSU_ID, ROTATION_OC_NAME.get(FACTION_BSU_ID), null)));

        REASSIGN_OC_FACTION.add(FACTION_PN_ID);
        REASSIGN_OC_FACTION.add(FACTION_HP_ID);
        REASSIGN_OC_FACTION.add(FACTION_CCRC_ID);
        REASSIGN_OC_FACTION.add(FACTION_SH_ID);
        REASSIGN_OC_FACTION.add(FACTION_NOV_ID);
        REASSIGN_OC_FACTION.add(FACTION_BSU_ID);

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

    /**
     * 合并两个不可变名单，返回一个新的不可变列表。
     *
     * @param first  第一段名单
     * @param second 第二段名单
     * @return 合并后的不可变名单
     */
    private static List<String> combine(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }
}