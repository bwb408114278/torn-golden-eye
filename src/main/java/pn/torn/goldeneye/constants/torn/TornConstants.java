package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Torn常量
 *
 * @author Bai
 * @version 0.1.0
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

    // ====================OC相关====================
    /**
     * OC轮转级别
     */
    public static final List<Integer> ROTATION_OC_RANK = new ArrayList<>();

    static {
        ROTATION_OC_RANK.add(7);
        ROTATION_OC_RANK.add(8);
    }

    /**
     * OC重载任务ID
     */
    public static final String TASK_ID_OC_RELOAD = "oc-reload";
    /**
     * OC重载任务ID
     */
    public static final String TASK_ID_OC_READY = "oc-ready-";
    /**
     * OC重载任务ID
     */
    public static final String TASK_ID_OC_JOIN = "oc-join-";
    /**
     * OC重载任务ID
     */
    public static final String TASK_ID_OC_COMPLETE = "oc-completed-";
    /**
     * OC校验任务ID
     */
    public static final String TASK_ID_OC_VALID = "oc-valid-";

    // ====================配置相关====================
    /**
     * 配置Key - 物品使用记录读取时间
     */
    public static final String SETTING_KEY_ITEM_USE_LOAD = "ITEM_USED_LOAD_DATE";
    /**
     * 配置Key - OC成功率读取时间
     */
    public static final String SETTING_KEY_OC_PASS_RATE_LOAD = "OC_PASS_RATE_LOAD_DATE";
    /**
     * 配置Key - OC读取时间
     */
    public static final String SETTING_KEY_OC_LOAD = "OC_LOAD_TIME";
    /**
     * 配置Key - 是否启用临时OC
     */
    public static final String SETTING_KEY_OC_TEMP_ENABLE = "OC_TEMP_ENABLE";
    /**
     * 配置Key - 临时队不能加入级别
     */
    public static final String SETTING_KEY_OC_TEMP_DISABLE_RANK = "OC_TEMP_BLOCK_RANK";
    /**
     * 配置Key - OC队伍招募ID
     */
    public static final String SETTING_KEY_OC_REC_ID = "OC_TEAM_REC_ID_";
    /**
     * 配置Key - OC队伍计划ID
     */
    public static final String SETTING_KEY_OC_PLAN_ID = "OC_TEAM_PLAN_ID_";

    // ====================物品相关====================
    /**
     * 物品名称 - 小红
     */
    public static final String ITEM_NAME_SMALL_RED = "Small First Aid Kit";
}