package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 人工重新运行指令的评估窗口。
 *
 * @param nextReplanAt   建议下次重新评估时间
 * @param latestReplanAt 最晚重新评估时间
 * @param reasonCodes    窗口原因码集合
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcReplanWindow(
        LocalDateTime nextReplanAt,
        LocalDateTime latestReplanAt,
        Set<OcPlanReasonCodeEnum> reasonCodes) {
    public OcReplanWindow {
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
    }
}
