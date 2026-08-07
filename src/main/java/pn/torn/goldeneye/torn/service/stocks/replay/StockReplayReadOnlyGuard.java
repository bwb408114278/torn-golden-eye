package pn.torn.goldeneye.torn.service.stocks.replay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.StockReplayReadOnlyProbeMapper;

/**
 * 隔离回放只读事务守卫。
 *
 * <p>隔离回放必须保证数据库会话只读且使用 Repeatable Read 隔离级别: 任何数据访问都在
 * 只读事务内执行,DAO 写入会被 PostgreSQL 以只读事务错误拒绝;Repeatable Read 保证同一
 * 回放请求的全部输入(bar/feature/月度状态)来自同一一致性快照,避免分块加载之间读到
 * 不同代际的数据。守卫承担两项职责:</p>
 *
 * <ol>
 *   <li>启动校验: 在独立只读事务内执行 {@code SELECT current_setting('transaction_read_only')}
 *       与 {@code SELECT current_setting('transaction_isolation')},断言只读标志为 {@code on}
 *       且隔离级别为 repeatable read,否则抛出 {@link IllegalStateException} 中止回放;</li>
 *   <li>只读事务回调执行器: 数据访问代码通过 {@link #inReadOnlyTransaction} 在
 *       {@code READ ONLY + REPEATABLE READ} 事务内执行,任何写操作由数据库只读事务约束拒绝。</li>
 * </ol>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Slf4j
@Component
public class StockReplayReadOnlyGuard {

    private final TransactionTemplate readOnlyTxTemplate;
    private final TransactionTemplate validationTxTemplate;
    private final StockReplayReadOnlyProbeMapper probeMapper;

    public StockReplayReadOnlyGuard(PlatformTransactionManager transactionManager,
                                    StockReplayReadOnlyProbeMapper probeMapper) {
        this.readOnlyTxTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTxTemplate.setReadOnly(true);
        this.readOnlyTxTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.validationTxTemplate = new TransactionTemplate(transactionManager);
        this.validationTxTemplate.setReadOnly(true);
        this.validationTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.validationTxTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.probeMapper = probeMapper;
    }

    /**
     * 在只读事务内执行回调,任何写操作将触发数据库只读事务错误。
     * <p>
     * 默认 REQUIRED 传播: 无外层事务时新建只读 + Repeatable Read 事务;已有外层事务时加入
     * (测试可注入可见数据)。
     *
     * @param callback 在只读事务内执行的回调
     * @param <T>      回调返回值的类型
     * @return 回调的返回值;回调无返回值时返回 null
     * @throws org.springframework.dao.DataAccessException 数据库访问失败时抛出
     */
    public <T> T inReadOnlyTransaction(TransactionCallback<T> callback) {
        return readOnlyTxTemplate.execute(callback);
    }

    /**
     * 启动校验: 在独立只读事务内断言当前事务为只读且隔离级别为 Repeatable Read,否则抛出异常中止回放。
     * <p>
     * 使用 {@code current_setting('transaction_read_only')} 校验只读事务已生效(结果为 on),
     * 使用 {@code current_setting('transaction_isolation')} 校验隔离级别为 repeatable read;
     * 任何DAO写操作都会在此只读事务内被PostgreSQL拒绝。
     *
     * @throws IllegalStateException 当前数据库事务非只读或隔离级别不正确时抛出,中止回放
     */
    public void verifyReadOnlySession() {
        validationTxTemplate.executeWithoutResult(status -> {
            String readOnly = probeMapper.selectTransactionReadOnly();
            if (!"on".equalsIgnoreCase(readOnly)) {
                throw new IllegalStateException(
                        "隔离回放要求只读数据库会话,但 transaction_read_only = " + readOnly);
            }
            String isolation = probeMapper.selectTransactionIsolationLevel();
            if (isolation == null || !isolation.toLowerCase().contains("repeatable read")) {
                throw new IllegalStateException(
                        "隔离回放要求 Repeatable Read 一致性快照,但 transaction_isolation = " + isolation);
            }
        });
        log.info("隔离回放只读 + Repeatable Read 会话校验通过");
    }
}
