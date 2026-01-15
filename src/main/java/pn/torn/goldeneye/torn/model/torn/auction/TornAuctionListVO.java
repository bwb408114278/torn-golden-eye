package pn.torn.goldeneye.torn.model.torn.auction;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Torn拍卖行列表响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
public class TornAuctionListVO {
    /**
     * 拍卖信息列表
     */
    @JsonProperty("auctionhouse")
    private List<TornAuctionVO> auctionList;
}