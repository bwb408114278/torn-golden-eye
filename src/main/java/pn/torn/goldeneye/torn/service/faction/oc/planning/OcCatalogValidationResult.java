package pn.torn.goldeneye.torn.service.faction.oc.planning;

import java.util.List;
import java.util.Set;

/**
 * OC规划目录校验结果。
 */
public record OcCatalogValidationResult(List<String> warnings, Set<String> invalidOcKeys) {
    public OcCatalogValidationResult {
        warnings = List.copyOf(warnings);
        invalidOcKeys = Set.copyOf(invalidOcKeys);
    }
}
