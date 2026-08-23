package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockMarketRoundMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.List;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundFactory;

/**
 * Torn股票策略轮次记录持久层类
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.07.24
 */
@Repository
public class TornStockMarketRoundDAO extends ServiceImpl<TornStockMarketRoundMapper, TornStockMarketRoundDO> {

    /**
     * 查询最后一个已完成的轮次
     *
     * @return 最近一个已完成轮次,无则返回null
     */
    public TornStockMarketRoundDO selectLastCompleted() {
        return baseMapper.selectLastCompleted();
    }

    /**
     * 查询指定时间之前(含该时间)生产可消费的轮次,批量获取避免N+1。
     * <p>
     * 与生产者创建最近已结束桶使用同一包含上界语义:定时入口先为{@code currentEndedBucket}
     * 幂等建立PENDING轮次,再以同一时间作为包含上界查询,保证新桶本次调度即被处理。
     * 状态过滤为显式生产白名单,数据修复终态{@code REPAIRED_DATA_ONLY}与
     * {@code COMPLETED}/{@code FAILED_FINAL}均不在可消费集合内。
     *
     * @param maxRoundTime 最大轮次时间(包含该时间)
     * @return 生产可消费轮次列表(按轮次时间升序)
     */
    public List<TornStockMarketRoundDO> selectPendingRoundsUpTo(LocalDateTime maxRoundTime) {
        return baseMapper.selectPendingRoundsUpTo(maxRoundTime);
    }

    /**
     * 按round_time普通读取轮次(不加行锁),用于历史重建完整性判定等只读阶段。
     *
     * @param roundTime 轮次时间
     * @return 轮次记录,无则返回null
     */
    public TornStockMarketRoundDO selectByRoundTime(LocalDateTime roundTime) {
        return baseMapper.selectByRoundTime(roundTime);
    }

    /**
     * 按round_time查询轮次并加行锁(FOR UPDATE),用于事务内锁定
     *
     * @param roundTime 轮次时间
     * @return 锁定的轮次记录,无则返回null
     */
    public TornStockMarketRoundDO selectByRoundTimeForUpdate(LocalDateTime roundTime) {
        return baseMapper.selectByRoundTimeForUpdate(roundTime);
    }

    /**
     * 幂等插入最近已结束桶的PENDING轮次。
     * <p>
     * 数据库部分唯一索引 {@code uk_stock_market_round_time} 的 {@code (round_time) WHERE deleted = 0}
     * 与 {@code ON CONFLICT DO NOTHING} 共同保证双入口/重启重试只落一行;
     * 禁止先SELECT再普通INSERT作为并发正确性保证,查询仅可用于日志。
     *
     * @param round 待插入的PENDING轮次(须填充roundTime与全部规则版本字段)
     * @return 实际插入行数(0表示已存在同round_time有效轮次)
     */
    public int insertPendingRoundIgnoreConflict(TornStockMarketRoundDO round) {
        return baseMapper.insertPendingRoundIgnoreConflict(round);
    }

    /**
     * 批量 UPSERT 数据修复轮次。
     * <p>
     * 仅用于全范围派生数据重建：一次多值 INSERT ... ON CONFLICT 将目标范围 bucket
     * 标记为 {@code REPAIRED_DATA_ONLY}；保留 {@code COMPLETED}/{@code FAILED_FINAL}
     * 终态不降级。单批大小由调用方控制在 500 条以内。
     *
     * @param rounds 待写入/更新的数据修复轮次（须经 {@code StockMarketRoundFactory} 初始化全部 NOT NULL 字段）
     * @return 实际受影响（新插入或被更新）的轮次数
     */
    public int upsertRepairedDataOnlyRounds(List<TornStockMarketRoundDO> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return 0;
        }
        return baseMapper.upsertRepairedDataOnlyRounds(rounds);
    }
}
