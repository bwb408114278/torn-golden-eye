package pn.torn.goldeneye.constants.bot;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Bot指令
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.04
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class BotCommands {
    // ====================OC相关====================
    /**
     * OC校准
     */
    public static final String OC_CHECK = "OC校准";
    /**
     * OC查询
     */
    public static final String OC_QUERY = "OC查询";
    /**
     * OC替补
     */
    public static final String OC_FREE = "OC替补";
    /**
     * OC成功率
     */
    public static final String OC_PASS_RATE = "OC成功率";
    /**
     * OC提醒
     */
    public static final String OC_NOTICE = "OC提醒";
    /**
     * 取消OC提醒
     */
    public static final String CANCEL_OC_NOTICE = "取消OC提醒";
    /**
     * 今日轮转
     */
    public static final String OC_ROTATION_QUERY = "今日轮转";
    /**
     * OC收益
     */
    public static final String OC_BENEFIT = "OC收益";
    /**
     * OC收益榜
     */
    public static final String OC_BENEFIT_RANK = "OC收益榜";

    // ====================帮派成员相关====================
    /**
     * 帮派物品记录
     */
    public static final String ITEM_USED = "帮派物品记录";
    /**
     * 小红毁灭者
     */
    public static final String SMALL_RED_BROKER = "小红毁灭者";

    // ====================其他功能相关====================
    /**
     * 股票分红购买
     */
    public static final String STOCK_DIVIDEND_CALC = "股票分红购买";

    // ====================管理功能相关====================
    /**
     * 当前任务
     */
    public static final String CURRENT_TASK = "当前任务";
    /**
     * 当前任务
     */
    public static final String REFRESH_CACHE = "刷新缓存";
    /**
     * 手册
     */
    public static final String DOC = "手册";
    /**
     * 手册
     */
    public static final String MANAGE_DOC = "管理手册";
    /**
     * 停止聊天
     */
    public static final String BLOCK_CHAT = "停止聊天";
    /**
     * 开始聊天
     */
    public static final String CANCEL_BLOCK_CHAT = "开始聊天";
    /**
     * 绑Key
     */
    public static final String BIND_KEY = "绑Key";
    /**
     * 8级混编
     */
    public static final String OC_RORATION_MIX = "8级混编";
    /**
     * 8级整编
     */
    public static final String OC_RORATION_WHOLE = "8级整编";
    /**
     * 8级混编
     */
    public static final String OC_SKIP_TEAM = "OC咸鱼队";
}