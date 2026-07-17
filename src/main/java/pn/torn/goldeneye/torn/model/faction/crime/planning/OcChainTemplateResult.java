package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;

/**
 * 高阶链模板构造结果。
 *
 * @param chains 通过完整性校验的高阶链模板
 * @param warnings 被阻断的链配置警告
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcChainTemplateResult(List<List<OcTeamDemand>> chains,
                                    List<String> warnings) {
    public OcChainTemplateResult {
        chains = chains == null ? List.of() : chains.stream().map(List::copyOf).toList();
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
