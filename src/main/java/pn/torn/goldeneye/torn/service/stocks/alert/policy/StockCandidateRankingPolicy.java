package pn.torn.goldeneye.torn.service.stocks.alert.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 买入候选排序策略，按质量分降序、股票ID升序确定正式槽位竞争的候选顺序。
 * <p>
 * 不同股票竞争正式槽位时，排序规则为：
 * <ol>
 *   <li>qualityScore 降序（质量分越高越优先）</li>
 *   <li>stocksId 升序（质量分相同时股票ID小的优先，保证确定性）</li>
 * </ol>
 * 同一股票多策略命中时的主策略选取不在本类职责范围内，由调用方在构造
 * {@link CandidateInfo} 时完成。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Slf4j
@Component
public class StockCandidateRankingPolicy {
    /**
     * 候选排序比较器：qualityScore DESC -> stocksId ASC
     */
    private static final Comparator<CandidateInfo> RANKING_COMPARATOR = Comparator
            .comparing(CandidateInfo::qualityScore, Comparator.reverseOrder())
            .thenComparing(CandidateInfo::stocksId, Comparator.naturalOrder());

    /**
     * 对候选列表按质量分降序、股票ID升序排序，返回新的有序列表。
     * <p>
     * 原列表不被修改，返回结果为不可变有序副本。
     *
     * @param candidates 待排序的候选列表，可为空
     * @return 排序后的候选列表，输入为空时返回空列表
     */
    public List<CandidateInfo> rank(List<CandidateInfo> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            log.debug("候选排序-输入为空，返回空列表");
            return List.of();
        }
        List<CandidateInfo> ranked = candidates.stream()
                .sorted(RANKING_COMPARATOR)
                .toList();
        log.info("候选排序-完成: 输入{}个候选, 排序后首位stocksId={}, score={}",
                candidates.size(),
                ranked.getFirst().stocksId(),
                ranked.getFirst().qualityScore());
        return ranked;
    }
}
