package pn.torn.goldeneye.base.torn;

import org.springframework.util.MultiValueMap;

/**
 * Torn请求参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
public interface TornReqParamV2 {
    /**
     * 请求路径
     *
     * @return 绝对路径
     */
    String uri();

    /**
     * 是否需要帮派权限
     *
     * @return true为是
     */
    boolean needFactionAccess();

    /**
     * 获取请求参数
     *
     * @return 请求参数
     */
    MultiValueMap<String, String> buildReqParam();
}