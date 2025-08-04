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
    // ====================用户相关====================
    /**
     * 同步用户
     */
    public static final String MATCH_USER = "同步用户";
    /**
     * 查询用户
     */
    public static final String QUERY_USER = "查询用户";

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
     * OC跳过
     */
    public static final String OC_SKIP = "OC跳过";
    /**
     * 取消OC跳过
     */
    public static final String CANCEL_OC_SKIP = "取消OC跳过";

    // ====================帮派成员相关====================
    /**
     * 同步成员
     */
    public static final String MATCH_MEMBER = "同步成员";
}