package pn.torn.goldeneye;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pn.torn.goldeneye.configuration.BotSocketClient;

/**
 * 不需要Socket的测试基类
 *
 * @author Bai
 * @version 0.1.0
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