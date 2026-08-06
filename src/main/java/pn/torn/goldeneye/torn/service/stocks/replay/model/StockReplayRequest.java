package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 隔离回放请求。
 *
 * <p>输入必须完全确定性: 起止时间、数据/规则版本、轨道集合与输出根目录在两次相同输入下
 * 生成相同 {@code runId} 与完全一致的产物内容。回放只读取输入数据,不写任何业务表。</p>
 *
 * @param startTime       回放开始时间(含),按15分钟桶对齐
 * @param endTime         回放结束时间(含),按15分钟桶对齐
 * @param barBuildVersion bar构建规则版本
 * @param featureVersion  特征计算规则版本
 * @param buyRuleVersion  买入规则版本
 * @param sellRuleVersion 退出规则版本
 * @param tracks          参与回放的轨道集合
 * @param outputRootDir   产物输出根目录(默认.hermes/output/vip-stock-replay)
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
        String outputRootDir
) {
}
