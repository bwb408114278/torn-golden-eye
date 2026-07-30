package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import pn.torn.goldeneye.torn.service.stocks.alert.StockReplayBoundary;

import java.util.EnumMap;
import java.util.Map;

/**
 * 隔离回放上下文，只持有内存状态和显式输入参数。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public final class StockReplayContext {
    private final StockReplayRequest request;
    private final StockReplayBoundary boundary;
    private final Map<StockReplayTrackEnum, StockReplayPortfolioState> portfolioStates;

    private StockReplayContext(StockReplayRequest request) {
        this.request = request;
        this.boundary = StockReplayBoundary.create(request.portfolioId());
        this.portfolioStates = new EnumMap<>(StockReplayTrackEnum.class);
        request.tracks().forEach(track -> portfolioStates.put(track,
                StockReplayPortfolioState.initial(track)));
    }

    /**
     * 创建新的独立回放上下文。
     *
     * @param request 回放请求
     * @return 回放上下文
     */
    public static StockReplayContext create(StockReplayRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("回放请求不能为空");
        }
        return new StockReplayContext(request);
    }

    public StockReplayRequest request() {
        return request;
    }

    public StockReplayBoundary boundary() {
        return boundary;
    }

    /**
     * 获取轨道内存组合状态。
     *
     * @param track 轨道
     * @return 轨道状态
     */
    public StockReplayPortfolioState portfolioState(StockReplayTrackEnum track) {
        StockReplayPortfolioState state = portfolioStates.get(track);
        if (state == null) {
            throw new IllegalArgumentException("轨道未被请求: " + track);
        }
        return state;
    }
}
