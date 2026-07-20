package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcCurrentOccupancySummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshPlanningContext;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于不可变快照生成匿名刷新指令的纯规划器。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.17
 */
@Component
@RequiredArgsConstructor
public class OcRefreshInstructionPlanner {
    private static final Duration SEARCH_TIMEOUT = Duration.ofMillis(1_000);
    private static final int MAX_REFRESH_SEARCH_COUNT = 20;

    private final OcRefreshSafetyRequestFactory requestFactory;
    private final OcRefreshModeSelector modeSelector;
    private final OcCurrentOccupancyCalculator occupancyCalculator;

    /**
     * 生成指定模式的刷新指令。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @param mode 刷新策略模式
     * @return 不包含成员分配的刷新操作指令
     */
    public OcRefreshInstructionPlan plan(OcPlanningSnapshot snapshot, OcPlanMode mode) {
        OcRefreshPlanningContext context = requestFactory.create(snapshot);
        boolean configurationValid = snapshot.policy().validationWarnings().isEmpty()
                && context.warnings().isEmpty();
        OcRefreshSafetyResult safety = configurationValid
                ? new OcRefreshSafetySolver(SEARCH_TIMEOUT, MAX_REFRESH_SEARCH_COUNT)
                .solve(context.request())
                : new OcRefreshSafetyResult(List.of(new OcRefreshVector(0, 0)),
                false, 0, List.of());
        OcRefreshVector selected = configurationValid
                ? modeSelector.select(safety, snapshot.policy(), mode)
                : new OcRefreshVector(0, 0);
        OcCurrentOccupancySummary occupancySummary = occupancyCalculator.calculate(snapshot);
        List<String> warnings = collectWarnings(snapshot, context, safety);
        return new OcRefreshInstructionPlan(snapshot.factionId(), snapshot.snapshotTime(), mode,
                context.plannedEmptyOcCounts(), selected.normalCount(), selected.highCount(),
                safety.lowerBound(), reason(selected, context, configurationValid),
                occupancySummary, warnings);
    }

    /**
     * 合并快照、策略、上下文和求解警告。
     *
     * @param snapshot 规划快照
     * @param context 刷新规划上下文
     * @param safety 安全边界结果
     * @return 合并后的警告
     */
    private List<String> collectWarnings(OcPlanningSnapshot snapshot,
                                         OcRefreshPlanningContext context,
                                         OcRefreshSafetyResult safety) {
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        warnings.addAll(snapshot.policy().validationWarnings());
        warnings.addAll(context.warnings());
        warnings.addAll(safety.warnings());
        return warnings;
    }

    /**
     * 根据配置状态、可用池和已选安全向量生成用户可读原因。
     *
     * @param selected 已选刷新向量
     * @param context 刷新规划上下文
     * @param configurationValid 配置是否有效
     * @return 刷新建议原因
     */
    private String reason(OcRefreshVector selected, OcRefreshPlanningContext context,
                          boolean configurationValid) {
        if (!configurationValid) {
            return "规划配置存在错误，已停止自动刷新建议";
        }
        if (selected.totalCount() > 0) {
            return "已按当前OC占用、达标成员和成员释放时间证明建议次数可承接";
        }
        if (context.request().normalTemplates().isEmpty()
                && context.request().highChains().isEmpty()) {
            return "当前没有有效的计划刷新池配置";
        }
        return "当前剩余达标成员无法证明可安全承接新的完整阵容";
    }
}
