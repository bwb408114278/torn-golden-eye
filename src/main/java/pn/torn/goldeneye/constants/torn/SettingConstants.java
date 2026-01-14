package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 配置常量
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.17
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class SettingConstants {
    /**
     * 银行利率
     */
    public static final String KEY_BANK_RATE = "BANK_RATE";
    /**
     * PT价值
     */
    public static final String KEY_POINT_VALUE = "POINT_VALUE";
    /**
     * 飞书当前拍卖表格ID
     */
    public static final String LARK_AUCTION_TABLE_ID = "LARK_AUCTION_TABLE_ID";
    /**
     * 飞书当前拍卖表格行数
     */
    public static final String LARK_AUCTION_TABLE_ROWS_COUNT = "LARK_AUCTION_TABLE_ROWS_COUNT";

    /**
     * 配置Key - 用户数据读取时间
     */
    public static final String KEY_KEY_DATA_LOAD = "KEY_LOAD_DATE";
    /**
     * 配置Key - 基础数据读取时间
     */
    public static final String KEY_BASE_DATA_LOAD = "BASE_DATA_LOAD_DATE";
    /**
     * 配置Key - 帮派数据读取时间
     */
    public static final String KEY_FACTION_DATA_LOAD = "FACTION_DATA_LOAD_DATE";
    /**
     * 配置Key - 帮派物资读取时间
     */
    public static final String KEY_FACTION_ARMORY_LOAD = "FACTION_ARMORY_LOAD_DATE";
    /**
     * 配置Key - 新闻记录读取时间
     */
    public static final String KEY_FACTION_NEWS_LOAD = "NEWS_LOAD_DATE";
    /**
     * 配置Key - 拍卖记录读取时间
     */
    public static final String KEY_AUCTION_LOAD = "AUCTION_LOAD_DATE";

    /**
     * 配置Key - OC成功率读取时间
     */
    public static final String KEY_USER_DATA_LOAD = "USER_DATA_LOAD_DATE";
    /**
     * 配置Key - OC收益读取时间
     */
    public static final String KEY_OC_BENEFIT_LOAD = "OC_BENEFIT_LOAD_TIME";
    /**
     * 配置Key - OC读取时间
     */
    public static final String KEY_OC_LOAD = "OC_LOAD_TIME";
    /**
     * 配置Key - RW读取时间
     */
    public static final String KEY_RW_LOAD = "RW_LOAD_TIME";
}