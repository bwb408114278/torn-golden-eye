package pn.torn.goldeneye.base.torn;

/**
 * Torn请求参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.07.22
 */
public interface TornReqParam {
    /**
     * 请求路径
     *
     * @return 绝对路径
     */
    String uri();

    /**
     * 获取ID
     *
     * @return ID
     */
    Long getId();

    /**
     * 获取请求参数
     *
     * @return 请求参数
     */
    String getSection();
}