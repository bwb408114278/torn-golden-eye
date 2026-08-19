package pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot;

import java.util.List;
import java.util.Set;

/**
 * OC规划目录校验结果。
 *
 * @param warnings      规划目录校验警告
 * @param invalidOcKeys 未通过校验的OC键集合
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
public record OcCatalogValidationResult(
        List<String> warnings,
        Set<String> invalidOcKeys) {
    public OcCatalogValidationResult {
        warnings = List.copyOf(warnings);
        invalidOcKeys = Set.copyOf(invalidOcKeys);
    }
}
