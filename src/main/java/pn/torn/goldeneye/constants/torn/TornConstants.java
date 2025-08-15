package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Torn常量
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornConstants {
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
     * OC重载任务ID
     */
    public static final String TASK_ID_OC_RELOAD = "oc-reload";
    /**
     * OC校验任务ID
     */
    public static final String TASK_ID_OC_VALID = "oc-valid-";

    /**
     * 配置Key - 物品使用记录读取时间
     */
    public static final String SETTING_KEY_ITEM_USE_LOAD = "ITEM_USED_LOAD_DATE";
    /**
     * 配置Key - OC读取时间
     */
    public static final String SETTING_KEY_OC_LOAD = "OC_LOAD_TIME";
    /**
     * 配置Key - 是否启用临时OC
     */
    public static final String SETTING_KEY_OC_TEMP_ENABLE = "OC_TEMP_ENABLE";
    /**
     * 配置Key - 临时OC队伍ID
     */
    public static final String SETTING_KEY_OC_TEMP_ID = "OC_TEMP_TEAM_ID";

    /**
     * 8级 oc chain名称
     */
    public static final String OC_RANK_8_CHAIN = "Stacking the Deck";
    /**
     * 物品名称 - 小红
     */
    public static final String ITEM_NAME_SMALL_RED = "Small First Aid Kit";
}