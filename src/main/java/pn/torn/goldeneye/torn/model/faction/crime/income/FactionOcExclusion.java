package pn.torn.goldeneye.torn.model.faction.crime.income;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帮派大锅饭普通收益排除规则。
 *
 * <p>一条扁平规则表示：对指定帮派，名单内的OC名称在满足生效时间条件时从普通收益数据源排除。
 * {@code effectiveFrom}为{@code null}表示该名单在所有历史月份都排除（原有大锅饭名单）；
 * 非{@code null}表示仅当OC完成时间达到该时间点（左闭区间）后才排除（本次新增名单）。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
@Data
@AllArgsConstructor
public class FactionOcExclusion {
    /**
     * 帮派ID。
     */
    private Long factionId;
    /**
     * 需要排除的OC名称列表。
     */
    private List<String> ocList;
    /**
     * 生效时间，为{@code null}表示始终排除；否则仅排除完成时间大于等于该时间点的记录。
     */
    private LocalDateTime effectiveFrom;
}
