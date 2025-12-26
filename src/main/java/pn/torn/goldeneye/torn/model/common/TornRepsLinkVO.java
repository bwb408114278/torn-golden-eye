package pn.torn.goldeneye.torn.model.common;

import lombok.Data;

/**
 * Torn响应链接
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Data
public class TornRepsLinkVO {
    /**
     * 上一页链接
     */
    private String prev;
    /**
     * 下一页链接
     */
    private String next;
}