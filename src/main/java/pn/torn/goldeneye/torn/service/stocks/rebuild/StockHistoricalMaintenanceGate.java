package pn.torn.goldeneye.torn.service.stocks.rebuild;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 股票历史数据维护共享互斥门。
 * <p>
 * Tornsy 分钟回填与全范围派生数据重建都会写相同的 bar/feature 表，因此必须共用
 * 同一个 JVM 内互斥门，不能分别维护两个 {@link AtomicBoolean}。任一维护任务执行期间，
 * 另一个入口必须返回 {@code ALREADY_PROCESSING} 或跳过，避免并行写派生表造成状态竞争。
 * <p>
 * 本类只负责互斥，不保存业务状态、不持久化、不感知任务类型；所有结束路径（成功、失败、
 * 执行器拒绝）必须由调用方在 {@code finally} 中调用 {@link #release()}。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Component
public class StockHistoricalMaintenanceGate {

    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 尝试获取历史数据维护互斥权。
     *
     * @return true 表示当前调用方获得互斥权；false 表示已有维护任务在执行中
     */
    public boolean tryAcquire() {
        return processing.compareAndSet(false, true);
    }

    /**
     * 释放历史数据维护互斥权。
     * <p>
     * 只有获得互斥权的调用方可以释放；重复释放仅记录 WARN，不改变状态。
     */
    public void release() {
        if (!processing.compareAndSet(true, false)) {
            log.warn("历史数据维护互斥门释放时未处于占用状态，忽略重复释放");
        }
    }
}
