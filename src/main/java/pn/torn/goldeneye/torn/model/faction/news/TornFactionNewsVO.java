package pn.torn.goldeneye.torn.model.faction.news;

import lombok.Data;

/**
 * 帮派新闻响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.07
 */
@Data
public class TornFactionNewsVO {
    /**
     * 新闻ID
     */
    private String id;
    /**
     * 文本
     */
    private String text;
    /**
     * 时间戳
     */
    private Long timestamp;
}