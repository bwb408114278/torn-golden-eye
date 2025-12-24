package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;

import java.util.List;

/**
 * 战斗Log列表响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackLogListVO {
    /**
     * log列表
     */
    private List<AttackLogVO> log;
    /**
     * 统计列表
     */
    private List<AttackSummaryVO> summary;
}