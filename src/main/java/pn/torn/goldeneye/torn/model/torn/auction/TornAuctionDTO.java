package pn.torn.goldeneye.torn.model.torn.auction;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn拍卖行请求
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
@AllArgsConstructor
public class TornAuctionDTO implements TornReqParamV2 {
    /**
     * 开始时间
     */
    private LocalDateTime from;
    /**
     * 结束时间
     */
    private LocalDateTime to;
    /**
     * 每页行数
     */
    private Integer limit;

    @Override
    public String uri() {
        return "/market/auctionhouse";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> resultMap = new LinkedMultiValueMap<>(4);
        resultMap.put("from", List.of(DateTimeUtils.convertToShortTimestamp(this.from).toString()));
        resultMap.put("to", List.of(DateTimeUtils.convertToShortTimestamp(this.to).toString()));
        resultMap.put("limit", List.of(this.limit.toString()));
        resultMap.put("sort", List.of("ASC"));
        return resultMap;
    }
}