package pn.torn.goldeneye.torn.service.stocks.rebuild;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 15 分钟策略特征滚动窗口。
 * <p>
 * 用于全范围派生数据重建和实时单桶路径：按时间升序逐条追加 bar，固定保留最近
 * {@code BARS_30D + 1} 条，避免每个 target 都复制、排序历史列表。窗口内部以环形数组
 * 支持 O(1) 追加/淘汰和相对索引取价，并为 96 / 672 / 2,880 三个价格窗口维护滚动 sum
 * 与 sumSquare，为 30 日 low/high 维护单调队列，为最近 2,880 条连续性维护增量计数。
 * <p>
 * 本类不访问 DAO、时钟、日志、状态机或写库；所有 BigDecimal 运算保持与旧实现相同的
 * scale=18、HALF_UP 语义。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public final class Stock15mFeatureRollingWindow {

    /**
     * 保留容量：最近 2,881 条。实时旧路径最多可携带 2,881 条历史 bar + 当前 bar，
     * 取最后 2,880 条作为策略连续性窗口。
     */
    private static final int CAPACITY = Stock15mFeatureBuildService.BARS_30D + 1;
    private static final int CALC_SCALE = 18;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final TornStockMarketBar15mDO[] bars = new TornStockMarketBar15mDO[CAPACITY];
    private final Deque<TornStockMarketBar15mDO> lowDeque = new ArrayDeque<>();
    private final Deque<TornStockMarketBar15mDO> highDeque = new ArrayDeque<>();

    private int head;
    private int size;

    private final SumState sum96 = new SumState();
    private final SumState sum672 = new SumState();
    private final SumState sum2880 = new SumState();

    /**
     * 最近 2,880 条窗口内的“不连续或不可用相邻关系”计数。
     */
    private int badAdjacencyCount;

    /**
     * 已物化 feature 次数。仅测试可观测钩子，不写日志/数据库/全局状态。
     */
    private int materializeCount;

    /**
     * 仅推进滚动窗口，不物化 feature。
     * <p>
     * 用于预热历史 bar：只更新窗口、sum、deque、连续性状态；绝不创建
     * {@link TornStockStrategyFeature15mDO}，也不执行 MA/Z-Score/标准差等指标计算。
     *
     * @param bar 新 bar（不允许为 null）
     */
    public void advance(TornStockMarketBar15mDO bar) {
        if (bar == null) {
            throw new IllegalArgumentException("滚动窗口不允许追加 null bar");
        }
        if (size == CAPACITY) {
            removeOldestPhysical();
        }
        addLast(bar);
    }

    /**
     * 仅按已追加的最后一条 bar 构造 feature，不改变窗口状态。
     * <p>
     * 当前 bar 不可用时返回 {@code null}；可用时返回完整特征。该方法是唯一允许
     * 触发指标计算/物化 feature 的公开入口。
     *
     * @return 当前 bar 的 feature；当前 bar 不可用时返回 {@code null}
     */
    public TornStockStrategyFeature15mDO materializeCurrent() {
        if (size == 0) {
            return null;
        }
        TornStockMarketBar15mDO currentBar = barAt(size - 1);
        TornStockStrategyFeature15mDO feature = Stock15mFeatureCalculator.buildFeature(currentBar, this);
        if (feature != null) {
            materializeCount++;
        }
        return feature;
    }

    /**
     * 当前保留的 bar 数量。
     *
     * @return bar 数量
     */
    public int size() {
        return size;
    }

    /**
     * 已物化的 feature 次数（仅测试可观测钩子，不参与业务逻辑）。
     *
     * @return 累计物化次数
     */
    int materializedFeatureCount() {
        return materializeCount;
    }

    /**
     * 计算最近指定窗口的简单移动平均；历史不足时返回 {@code null}。
     *
     * @param window 窗口 bar 数
     * @return 移动平均或 {@code null}
     */
    public BigDecimal calculateMa(int window) {
        if (size < window) {
            return null;
        }
        BigDecimal sum = sum(window);
        return sum.divide(BigDecimal.valueOf(window), CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算最近指定窗口的标准差；均线为空或历史不足时返回 {@code null}。
     *
     * @param window 窗口 bar 数
     * @param ma     该窗口已按 scale=18 四舍五入的均线
     * @return 标准差或 {@code null}
     */
    public BigDecimal calculateStd(int window, BigDecimal ma) {
        if (ma == null || size < window) {
            return null;
        }
        BigDecimal sum = sum(window);
        BigDecimal sumSq = sumSq(window);
        BigDecimal variance = sumSq.subtract(ma.multiply(sum).multiply(TWO))
                .add(ma.multiply(ma).multiply(BigDecimal.valueOf(window)))
                .divide(BigDecimal.valueOf(window), CALC_SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(CALC_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算窗口前至今的涨跌幅；历史不足或基准价为 0 时返回 {@code null}。
     *
     * @param windowAgo 回溯 bar 数
     * @return 涨跌幅或 {@code null}
     */
    public BigDecimal calculateReturn(int windowAgo) {
        if (size <= windowAgo) {
            return null;
        }
        BigDecimal pastPrice = priceAt(size - 1 - windowAgo);
        BigDecimal currentPrice = priceAt(size - 1);
        if (pastPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return currentPrice.divide(pastPrice, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 最近 30 日窗口最低价（历史不足时取全部已有 bar）。
     *
     * @return 最低价；窗口为空时返回 {@code null}
     */
    public BigDecimal low30d() {
        TornStockMarketBar15mDO bar = lowDeque.peekFirst();
        return bar == null ? null : bar.getLastPrice();
    }

    /**
     * 最近 30 日窗口最高价（历史不足时取全部已有 bar）。
     *
     * @return 最高价；窗口为空时返回 {@code null}
     */
    public BigDecimal high30d() {
        TornStockMarketBar15mDO bar = highDeque.peekFirst();
        return bar == null ? null : bar.getLastPrice();
    }

    /**
     * 最近 2,880 条是否全部严格连续且可用。
     *
     * @return 策略就绪返回 {@code true}
     */
    public boolean isStrategyReady() {
        if (size < Stock15mFeatureBuildService.BARS_30D) {
            return false;
        }
        return badAdjacencyCount == 0;
    }

    /**
     * 返回按时间升序的逻辑索引对应的 bar。
     *
     * @param index 0 为最早保留 bar，{@code size-1} 为最新 bar
     * @return bar
     */
    TornStockMarketBar15mDO barAt(int index) {
        return bars[(head + index) % CAPACITY];
    }

    /**
     * 返回按时间升序的逻辑索引对应的价格。
     *
     * @param index 0 为最早保留 bar，{@code size-1} 为最新 bar
     * @return 价格
     */
    private BigDecimal priceAt(int index) {
        return barAt(index).getLastPrice();
    }

    /**
     * 从物理环形数组移除最老 bar。最老 bar 已不在 96/672/2,880 窗口中，
     * 因此滚动 sum/sumSquare 与 low/high 队列均无需变化。
     */
    private void removeOldestPhysical() {
        bars[head] = null;
        head = (head + 1) % CAPACITY;
        size--;
    }

    /**
     * 将 bar 追加到窗口末尾，并增量维护各窗口聚合。
     *
     * @param bar 新 bar
     */
    private void addLast(TornStockMarketBar15mDO bar) {
        int index = (head + size) % CAPACITY;
        bars[index] = bar;
        size++;

        addToSums(bar);
        addToLowHigh(bar);

        if (size > 1
                && !Stock15mBarBuildService.isConsecutive(barAt(size - 2), barAt(size - 1))) {
            badAdjacencyCount++;
        }
        if (size > Stock15mFeatureBuildService.BARS_30D) {
            if (!Stock15mBarBuildService.isConsecutive(barAt(0), barAt(1))) {
                badAdjacencyCount--;
            }
            dropFromLowHigh(barAt(0));
        }
    }

    /**
     * 向三个价格窗口的 sum/sumSquare 增量追加并淘汰窗口外价格。
     *
     * @param bar 新 bar
     */
    private void addToSums(TornStockMarketBar15mDO bar) {
        BigDecimal price = bar.getLastPrice();
        addToWindowSum(Stock15mFeatureBuildService.BARS_PER_DAY, price);
        addToWindowSum(Stock15mFeatureBuildService.BARS_7D, price);
        addToWindowSum(Stock15mFeatureBuildService.BARS_30D, price);
    }

    /**
     * 更新单个窗口的 sum/sumSquare。
     *
     * @param window 窗口大小
     * @param price  新价格
     */
    private void addToWindowSum(int window, BigDecimal price) {
        SumState state = switch (window) {
            case Stock15mFeatureBuildService.BARS_PER_DAY -> sum96;
            case Stock15mFeatureBuildService.BARS_7D -> sum672;
            default -> sum2880;
        };
        state.sum = state.sum.add(price);
        state.sumSq = state.sumSq.add(price.multiply(price));
        if (size > window) {
            BigDecimal droppedPrice = barAt(size - window - 1).getLastPrice();
            state.sum = state.sum.subtract(droppedPrice);
            state.sumSq = state.sumSq.subtract(droppedPrice.multiply(droppedPrice));
        }
    }

    /**
     * 返回指定窗口的 sum。
     *
     * @param window 窗口大小
     * @return sum
     */
    private BigDecimal sum(int window) {
        if (window == Stock15mFeatureBuildService.BARS_PER_DAY) {
            return sum96.sum;
        }
        if (window == Stock15mFeatureBuildService.BARS_7D) {
            return sum672.sum;
        }
        return sum2880.sum;
    }

    /**
     * 返回指定窗口的 sumSquare。
     *
     * @param window 窗口大小
     * @return sumSquare
     */
    private BigDecimal sumSq(int window) {
        if (window == Stock15mFeatureBuildService.BARS_PER_DAY) {
            return sum96.sumSq;
        }
        if (window == Stock15mFeatureBuildService.BARS_7D) {
            return sum672.sumSq;
        }
        return sum2880.sumSq;
    }

    /**
     * 将新 bar 加入 30 日 low/high 单调队列。
     *
     * @param bar 新 bar
     */
    private void addToLowHigh(TornStockMarketBar15mDO bar) {
        BigDecimal price = bar.getLastPrice();
        while (!lowDeque.isEmpty()
                && lowDeque.peekLast().getLastPrice().compareTo(price) >= 0) {
            lowDeque.pollLast();
        }
        lowDeque.addLast(bar);
        while (!highDeque.isEmpty()
                && highDeque.peekLast().getLastPrice().compareTo(price) <= 0) {
            highDeque.pollLast();
        }
        highDeque.addLast(bar);
    }

    /**
     * 从 low/high 队列移除已滑出 30 日窗口的 bar。
     *
     * @param dropped 已滑出窗口的最老 bar
     */
    private void dropFromLowHigh(TornStockMarketBar15mDO dropped) {
        if (lowDeque.peekFirst() == dropped) {
            lowDeque.pollFirst();
        }
        if (highDeque.peekFirst() == dropped) {
            highDeque.pollFirst();
        }
    }

    /**
     * 单个滚动价格窗口的 sum/sumSquare 可变状态。
     */
    private static final class SumState {
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal sumSq = BigDecimal.ZERO;
    }

}
