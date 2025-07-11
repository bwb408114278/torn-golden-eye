package pn.torn.goldeneye.base.bot;

/**
 * Bot请求参数 - Socket请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.10
 */
public interface BotSocketReqParam {
    /**
     * 操作类型
     *
     * @return 操作类型
     */
    String getAction();

    /**
     * 请求参数
     *
     * @return 请求参数
     */
    default Object getParams() {
        return null;
    }
}