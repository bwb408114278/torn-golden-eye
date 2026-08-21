package pn.torn.goldeneye.torn.model.faction.armory;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn帮派物资请求
 *
 * @author Bai
 * @version 1.3.10
 * @since 2025.12.30
 */
@Data
@AllArgsConstructor
public class TornFactionArmoryDTO implements TornReqParamV2 {
    private String category;
    private int limit;
    private int offset;

    @Override
    public String uri() {
        return "/faction/inventory";
    }

    @Override
    public boolean needFactionAccess() {
        return true;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> resultMap = new LinkedMultiValueMap<>(3);
        resultMap.put("cat", List.of(category));
        resultMap.put("limit", List.of(String.valueOf(limit)));
        resultMap.put("offset", List.of(String.valueOf(offset)));
        return resultMap;
    }
}
