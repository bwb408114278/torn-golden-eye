package pn.torn.goldeneye.base.larksuite;

import org.springframework.http.HttpMethod;

import java.util.Map;

/**
 * 飞书手动请求参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.05
 */
public interface LarkSuiteManualReqParam {
    /**
     * 请求路径
     *
     * @return 绝对路径
     */
    String uri();

    /**
     * 获取请求Url参数
     *
     * @return Url参数
     */
    Map<String, Object> buildUrlParam();

    /**
     * 获取请求Body参数
     *
     * @return Body参数
     */
    Map<String, Object> buildBodyParam();

    /**
     * 请求Method
     */
    default HttpMethod method() {
        return HttpMethod.POST;
    }
}