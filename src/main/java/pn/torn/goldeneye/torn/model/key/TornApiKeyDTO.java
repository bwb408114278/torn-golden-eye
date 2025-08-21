package pn.torn.goldeneye.torn.model.key;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn Api Key请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
public class TornApiKeyDTO implements TornReqParamV2 {
    /**
     * Key
     */
    private String key;

    public TornApiKeyDTO(String key) {
        this.key = key;
    }

    @Override
    public String uri() {
        return "/key/info";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(1);
        param.put("key", List.of(key));
        return param;
    }
}