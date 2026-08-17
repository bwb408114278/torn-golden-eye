package pn.torn.goldeneye.repository.mapper.faction.oc;

import org.apache.ibatis.annotations.Mapper;

/**
 * OC规划隔离回放只读会话探针。仅查询当前会话的transaction_read_only与
 * transaction_isolation，用于fail-closed断言，不参与OC业务SQL正确性覆盖。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Mapper
public interface OcPlanningReadOnlyProbeMapper {

    /**
     * 查询当前会话事务只读标志。
     *
     * @return on(只读)/off(可写)
     */
    String selectTransactionReadOnly();

    /**
     * 查询当前会话事务隔离级别。
     *
     * @return 隔离级别，如repeatable read/read committed
     */
    String selectTransactionIsolationLevel();

    /**
     * 在只读事务内执行受控临时表DDL，用于断言PostgreSQL拒绝只读事务写操作。
     */
    void createProbeTemporaryTable();
}
