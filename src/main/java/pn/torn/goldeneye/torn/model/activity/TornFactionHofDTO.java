package pn.torn.goldeneye.torn.model.activity;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn 帮派名人堂请求（Rank War段位）
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.08
 */
@Data
@AllArgsConstructor
public class TornFactionHofDTO implements TornReqParamV2 {
    /**
     * 类别：rank
     */
    private final String cat;
    /**
     * 每页行数
     */
    private final int limit;
    /**
     * 偏移量
     */
    private final int offset;

    @Override
    public String uri() {
        return "/torn/factionhof";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("cat", cat);
        params.add("limit", String.valueOf(limit));
        params.add("offset", String.valueOf(offset));
        return params;
    }
}
