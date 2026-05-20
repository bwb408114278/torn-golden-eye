package pn.torn.goldeneye.configuration.socket;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 不需要Socket的测试基类
 *
 * @author Bai
 * @version 1.1.3
 * @since 2025.07.25
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseWithoutSocketTest {
    @MockitoBean
    private BotSocketClient socketClient;

    @BeforeAll
    public void skipSocket() {
        Mockito.doNothing().when(socketClient).init();
    }
}