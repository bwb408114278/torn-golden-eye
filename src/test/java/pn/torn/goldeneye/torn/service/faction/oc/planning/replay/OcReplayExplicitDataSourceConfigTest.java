package pn.torn.goldeneye.torn.service.faction.oc.planning.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC隔离回放显式数据库配置测试。验证回放数据源工厂在无任何显式配置时
 * 于建连前以非敏感提示fail-closed，不回退任何默认账号、主机或口令；
 * 该异常发生在DataSource Bean创建阶段，等价于显式回放在上下文启动期失败。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.17
 */
@DisplayName("OC隔离回放显式数据库配置")
class OcReplayExplicitDataSourceConfigTest {

    private final OcPlannerReplayTestConfiguration configuration =
            new OcPlannerReplayTestConfiguration();

    @Test
    @DisplayName("不提供任何显式回放配置时数据源必须在建连前失败")
    void shouldFailClosedBeforeConnectionWhenExplicitConfigMissing() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> configuration.dataSource("", "", ""),
                "无任何显式配置时必须在建连前失败");
        assertTrue(error.getMessage().contains("OC_REPLAY_DB_URL"),
                "失败消息必须指明缺失的配置项");
        assertFalse(error.getMessage().contains("jdbc:")
                        || error.getMessage().contains("postgres"),
                "失败消息不得回显连接值");
    }

    @Test
    @DisplayName("任一显式回放配置缺失时都必须失败")
    void shouldFailClosedWhenAnySingleConfigMissing() {
        String url = "jdbc:postgresql://localhost:14321/golden-eye";
        assertThrows(IllegalStateException.class,
                () -> configuration.dataSource("", "replay", "secret"),
                "URL缺失时必须失败");
        assertThrows(IllegalStateException.class,
                () -> configuration.dataSource(url, "", "secret"),
                "用户名缺失时必须失败");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> configuration.dataSource(url, "replay", ""),
                "密码缺失时必须失败");
        assertEquals("隔离回放缺少显式数据库配置 OC_REPLAY_DB_PASSWORD；"
                        + "请通过环境变量或本地未跟踪的replay-local.properties提供只读回放数据源",
                error.getMessage());
    }
}
