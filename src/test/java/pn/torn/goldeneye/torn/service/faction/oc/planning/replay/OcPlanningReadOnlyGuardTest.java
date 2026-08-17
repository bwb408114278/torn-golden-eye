package pn.torn.goldeneye.torn.service.faction.oc.planning.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import pn.torn.goldeneye.repository.mapper.faction.oc.OcPlanningReadOnlyProbeMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC规划隔离回放只读守卫测试。默认不创建上下文也不连接数据库，
 * 仅在{@code -Doc.replay.enabled=true}时执行。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OcPlannerReplayTestConfiguration.class)
@EnabledIfSystemProperty(named = "oc.replay.enabled", matches = "true")
@DisplayName("OC规划隔离回放只读守卫")
class OcPlanningReadOnlyGuardTest {

    @Autowired
    private OcPlanningReadOnlyGuard guard;
    @Autowired
    private OcPlanningReadOnlyProbeMapper probeMapper;

    @Test
    @DisplayName("只读事务应暴露transaction_read_only=on且隔离级别为repeatable read")
    void shouldExposeReadOnlyRepeatableReadSession() {
        guard.verifyReadOnlySession();

        String readOnly = guard.inReadOnlyTransaction(status ->
                probeMapper.selectTransactionReadOnly());
        String isolation = guard.inReadOnlyTransaction(status ->
                probeMapper.selectTransactionIsolationLevel());

        assertEquals("on", readOnly);
        assertTrue(isolation.toLowerCase().contains("repeatable read"));
    }

    @Test
    @DisplayName("只读事务内的受控临时表DDL应被PostgreSQL拒绝")
    void shouldRejectWriteInsideReadOnlyTransaction() {
        assertThrows(Exception.class, () -> guard.inReadOnlyTransaction(status -> {
            probeMapper.createProbeTemporaryTable();
            return null;
        }));
    }
}
