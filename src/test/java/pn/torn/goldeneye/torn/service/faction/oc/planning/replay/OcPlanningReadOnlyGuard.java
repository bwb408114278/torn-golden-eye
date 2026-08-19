package pn.torn.goldeneye.torn.service.faction.oc.planning.replay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.repository.mapper.faction.oc.OcPlanningReadOnlyProbeMapper;

/**
 * OC规划隔离回放只读事务守卫。复用股票隔离回放已验证的事务模式：
 * REQUIRES_NEW + READ ONLY + REPEATABLE READ，并在事务内用probe Mapper
 * 查询transaction_read_only与transaction_isolation后fail-closed。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Slf4j
public class OcPlanningReadOnlyGuard {

    private final TransactionTemplate readOnlyTxTemplate;
    private final TransactionTemplate validationTxTemplate;
    private final OcPlanningReadOnlyProbeMapper probeMapper;

    /**
     * 创建只读事务守卫。
     *
     * @param transactionManager 事务管理器
     * @param probeMapper        只读会话探针Mapper
     */
    public OcPlanningReadOnlyGuard(PlatformTransactionManager transactionManager,
                                   OcPlanningReadOnlyProbeMapper probeMapper) {
        this.readOnlyTxTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTxTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnlyTxTemplate.setReadOnly(true);
        this.readOnlyTxTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.validationTxTemplate = new TransactionTemplate(transactionManager);
        this.validationTxTemplate.setReadOnly(true);
        this.validationTxTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.validationTxTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.probeMapper = probeMapper;
    }

    /**
     * 在独立的只读事务内执行回调，任何写操作将触发数据库只读事务错误。
     *
     * @param callback 在只读事务内执行的回调
     * @param <T>      回调返回值的类型
     * @return 回调的返回值；回调无返回值时返回null
     */
    public <T> T inReadOnlyTransaction(TransactionCallback<T> callback) {
        return readOnlyTxTemplate.execute(status -> {
            verifyReadOnlySessionInTransaction();
            return callback.doInTransaction(status);
        });
    }

    /**
     * 启动校验：在独立只读事务内断言只读且Repeatable Read。
     */
    public void verifyReadOnlySession() {
        validationTxTemplate.executeWithoutResult(status ->
                verifyReadOnlySessionInTransaction());
        log.info("OC规划隔离回放只读 + Repeatable Read 会话校验通过");
    }

    /**
     * 在同一输入事务内执行只读与隔离级别probe，不满足即fail-closed。
     */
    private void verifyReadOnlySessionInTransaction() {
        String readOnly = probeMapper.selectTransactionReadOnly();
        if (!"on".equalsIgnoreCase(readOnly)) {
            throw new IllegalStateException(
                    "OC规划隔离回放要求只读数据库会话,但 transaction_read_only = " + readOnly);
        }
        String isolation = probeMapper.selectTransactionIsolationLevel();
        if (isolation == null || !isolation.toLowerCase().contains("repeatable read")) {
            throw new IllegalStateException(
                    "OC规划隔离回放要求 Repeatable Read 一致性快照,但 transaction_isolation = "
                            + isolation);
        }
    }
}
