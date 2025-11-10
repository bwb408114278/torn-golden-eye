package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户收益查询VO
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
@AllArgsConstructor
public class UserIncomeVO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 时间范围开始
     */
    private LocalDateTime startTime;
    /**
     * 时间范围结束
     */
    private LocalDateTime endTime;
    /**
     * 参与OC数量
     */
    private Integer ocCount;
    /**
     * 收益明细列表
     */
    private List<IncomeDetailVO> details;
}