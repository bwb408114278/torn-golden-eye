package pn.torn.goldeneye.torn.model.torn.items;

import lombok.Data;

import java.util.List;

/**
 * Torn物品列表响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornItemsListVO {
    /**
     * 物品列表
     */
    private List<TornItemsVO> items;
}