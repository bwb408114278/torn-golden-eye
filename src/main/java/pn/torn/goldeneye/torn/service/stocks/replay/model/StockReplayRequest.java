package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 隔离回放请求。
 *
 * <p>输入必须完全确定性: 起止时间、数据/规则版本、轨道集合、处理模式与输出根目录在两次
 * 相同输入下生成相同 {@code runId} 与完全一致的产物内容。回放只读取输入数据,不写任何业务表。</p>
 *
 * <p>处理模式与恢复时刻契约见 {@link StockReplayProcessingModeEnum}: 未显式提供模式时,由
 * {@code StockReplayRunner.normalize}归一化为 {@code ONLINE_BASELINE} 且 {@code recoveredAt=null};
 * 模式与时间字段不匹配时必须fail-fast。模式与 {@code recoveredAt} 参与 {@code runId} 计算,
 * 不同处理语义不得占用同一产物目录。</p>
 *
 * @param startTime       回放开始时间(含),按15分钟桶对齐
 * @param endTime         回放结束时间(含),按15分钟桶对齐
 * @param barBuildVersion bar构建规则版本
 * @param featureVersion  特征计算规则版本
 * @param buyRuleVersion  买入规则版本
 * @param sellRuleVersion 退出规则版本
 * @param tracks          参与回放的轨道集合
 * @param outputRootDir   产物输出根目录(默认.hermes/output/vip-stock-replay)
 * @param processingMode  实际处理模式(为空时归一化为ONLINE_BASELINE)
 * @param recoveredAt     晚恢复模拟时刻(仅RESTART_STRESS模式必填,不得早于其处理的回放轮次)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayRequest(
        LocalDateTime startTime,
        LocalDateTime endTime,
        String barBuildVersion,
        String featureVersion,
        String buyRuleVersion,
        String sellRuleVersion,
        Set<StockReplayTrackEnum> tracks,
        String outputRootDir,
        StockReplayProcessingModeEnum processingMode,
        LocalDateTime recoveredAt
) {

    /**
     * 后向兼容构造器: 未显式提供处理模式时,模式与恢复时刻均为空,由
     * {@code StockReplayRunner.normalize}归一化为{@code ONLINE_BASELINE}。
     *
     * @param startTime       回放开始时间(含)
     * @param endTime         回放结束时间(含)
     * @param barBuildVersion bar构建规则版本
     * @param featureVersion  特征计算规则版本
     * @param buyRuleVersion  买入规则版本
     * @param sellRuleVersion 退出规则版本
     * @param tracks          参与回放的轨道集合
     * @param outputRootDir   产物输出根目录
     */
    public StockReplayRequest(LocalDateTime startTime,
                              LocalDateTime endTime,
                              String barBuildVersion,
                              String featureVersion,
                              String buyRuleVersion,
                              String sellRuleVersion,
                              Set<StockReplayTrackEnum> tracks,
                              String outputRootDir) {
        this(startTime, endTime, barBuildVersion, featureVersion, buyRuleVersion, sellRuleVersion,
                tracks, outputRootDir, null, null);
    }
}
