package pn.torn.goldeneye.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.utils.JsonUtils;

/**
 * Torn API 请求上下文
 *
 */
record TornApiRequestContext(
        String uri,
        TornApiKeyDO apiKey,
        ResponseEntity<String> response) {
    boolean hasError() {
        return response != null && response.getBody() != null
                && JsonUtils.existsNode(response.getBody(), "error");
    }

    Integer getErrorCode() {
        if (!hasError()) return null;
        JsonNode node = JsonUtils.getNode(response.getBody(), "error.code");
        return node != null ? node.asInt() : null;
    }
}