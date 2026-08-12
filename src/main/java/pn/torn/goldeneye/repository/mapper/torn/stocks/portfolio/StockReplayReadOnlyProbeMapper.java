package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.apache.ibatis.annotations.Mapper;

/**
 * 隔离回放只读会话探针数据库访问层。
 *
 * <p>查询当前数据库会话的 {@code transaction_read_only} 与 {@code transaction_isolation}
 * 设置,用于断言隔离回放处于只读且 Repeatable Read 事务中。SQL 按项目规范收敛于对应 XML,
 * 不在业务代码中内嵌。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Mapper
public interface StockReplayReadOnlyProbeMapper {

    /**
     * 查询当前会话事务只读标志。
     *
     * @return on(只读)/off(可写)
     */
    String selectTransactionReadOnly();

    /**
     * 查询当前会话事务隔离级别。
     *
     * @return 隔离级别(如 repeatable read/read committed)
     */
    String selectTransactionIsolationLevel();
}
