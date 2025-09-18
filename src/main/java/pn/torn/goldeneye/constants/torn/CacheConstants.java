package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 缓存常量
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.17
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class CacheConstants {
    /**
     * 系统管理员Key
     */
    public static final String KEY_SYS_SETTING = "sys:setting";


    /**
     * OC设置Key
     */
    public static final String KEY_TORN_SETTING_OC = "torn:setting:oc";
    /**
     * OC岗位设置Key
     */
    public static final String KEY_TORN_SETTING_OC_SLOT = "torn:setting:oc:slot";
}