package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 缓存常量
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.09.17
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class CacheConstants {
    /**
     * 用户Key
     */
    public static final String KEY_TORN_USER = "torn:user";
    /**
     * 用户QQKey
     */
    public static final String KEY_TORN_USER_QQ = "torn:user:qq";
    /**
     * 用户Map Key
     */
    public static final String KEY_TORN_USER_MAP = "torn:user:map";

    /**
     * 系统设置Key
     */
    public static final String KEY_SYS_SETTING = "sys:setting";
    /**
     * 屏蔽词设置Key
     */
    public static final String KEY_SYS_BLOCK_WORD = "sys:block:word";
    /**
     * 物品Key
     */
    public static final String KEY_TORN_ITEM = "torn:items";
    /**
     * 物品Key
     */
    public static final String KEY_TORN_ITEM_MAP = "torn:items:map";
    /**
     * 物品Key, 名称Map
     */
    public static final String KEY_TORN_ITEM_NAME_MAP = "torn:items:name:map";
    /**
     * 物品Key, 名称排序List
     */
    public static final String KEY_TORN_ITEM_NAME_SORT_LIST = "torn:items:name:sort:list";

    /**
     * 帮派设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION = "torn:setting:faction";
    /**
     * 帮派ID设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION_ID = "torn:setting:faction:id";
    /**
     * 帮派ID设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION_GROUP_ID = "torn:setting:faction:group";
    /**
     * 帮派ID设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION_GROUP_SINGLE = "torn:setting:faction:group:single";
    /**
     * 帮派别名设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION_ALIAS = "torn:setting:faction:alias";
    /**
     * 帮派OC禁用设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION_OC_DISABLE = "torn:setting:faction:oc:disable";
    /**
     * 帮派OC岗位设置Key
     */
    public static final String KEY_TORN_SETTING_FACTION_OC_SLOT = "torn:setting:faction:oc:slot";
    /**
     * OC设置Key
     */
    public static final String KEY_TORN_SETTING_OC = "torn:setting:oc";
    /**
     * OC岗位设置Key
     */
    public static final String KEY_TORN_SETTING_OC_SLOT = "torn:setting:oc:slot";
    /**
     * OC系数设置Key
     */
    public static final String KEY_TORN_SETTING_OC_COEFFICIENT = "torn:setting:oc:coefficient";
}