package pn.torn.goldeneye.configuration;

import org.springframework.http.ResponseEntity;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

/**
 * Torn API 请求执行器
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.06
 */
@FunctionalInterface
interface TornApiRequestExecutor {
    /**
     * 执行请求
     *
     * @param apiKey 请求用的ApiKey
     * @return 请求结果
     */
    ResponseEntity<String> execute(TornApiKeyDO apiKey) throws RuntimeException;
}