package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 配置常量
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.17
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class SettingConstants {
    /**
     * 配置Key - 帮派成员读取时间
     */
    public static final String SETTING_KEY_FACTION_MEMBER_LOAD = "FACTION_MEMBER_LOAD_DATE";
    /**
     * 配置Key - 物品使用记录读取时间
     */
    public static final String SETTING_KEY_ITEM_USE_LOAD = "ITEM_USED_LOAD_DATE";
    /**
     * 配置Key - OC成功率读取时间
     */
    public static final String SETTING_KEY_OC_PASS_RATE_LOAD = "OC_PASS_RATE_LOAD_DATE";
    /**
     * 配置Key - OC收益读取时间
     */
    public static final String SETTING_KEY_OC_BENEFIT_LOAD = "OC_BENEFIT_LOAD_TIME";
    /**
     * 配置Key - OC读取时间
     */
    public static final String SETTING_KEY_OC_LOAD = "OC_LOAD_TIME";
    /**
     * 配置Key - 临时队不能加入级别
     */
    public static final String SETTING_KEY_OC_ENABLE_RANK = "OC_ENABLE_RANK_";
    /**
     * 配置Key - 临时队不能加入级别
     */
    public static final String SETTING_KEY_OC_BLOCK_RANK = "OC_BLOCK_RANK_";
    /**
     * 配置Key - OC队伍招募ID
     */
    public static final String SETTING_KEY_OC_REC_ID = "OC_TEAM_REC_ID_";
    /**
     * 配置Key - OC队伍计划ID
     */
    public static final String SETTING_KEY_OC_PLAN_ID = "OC_TEAM_PLAN_ID_";
}