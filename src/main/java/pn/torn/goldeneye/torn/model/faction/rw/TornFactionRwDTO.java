package pn.torn.goldeneye.torn.model.faction.rw;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn帮派Rw请求
 *
 * @author Bai
 * @version 1.6.4
 * @since 2025.12.25
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TornFactionRwDTO implements TornReqParamV2 {
    /**
     * 排序方向（ASC/DESC），null时不携带该参数
     */
    private String sort;
    /**
     * 单页返回场次数量，null时不携带该参数
     */
    private Integer limit;
    /**
     * 结果偏移量，null时不携带该参数
     */
    private Integer offset;

    @Override
    public String uri() {
        return "/faction/rankedwars";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> resultMap = new LinkedMultiValueMap<>(3);
        if (this.sort != null) {
            resultMap.put("sort", List.of(this.sort));
        }
        if (this.limit != null) {
            resultMap.put("limit", List.of(this.limit.toString()));
        }
        if (this.offset != null) {
            resultMap.put("offset", List.of(this.offset.toString()));
        }

        return resultMap;
    }
}
