package pn.torn.goldeneye.torn.model.faction.news;

import lombok.Data;

import java.util.List;

/**
 * 帮派新闻列表响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Data
public class TornFactionNewsListVO {
    /**
     * 新闻列表
     */
    private List<TornFactionNewsVO> news;
}
