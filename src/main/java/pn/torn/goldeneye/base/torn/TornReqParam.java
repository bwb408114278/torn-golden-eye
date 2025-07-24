package pn.torn.goldeneye.base.torn;

/**
 * Torn请求参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
public interface TornReqParam {
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