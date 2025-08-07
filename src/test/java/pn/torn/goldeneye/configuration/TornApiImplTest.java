package pn.torn.goldeneye.configuration;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.BaseWithoutSocketTest;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;

/**
 * Torn Api构建测试
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.25
 */
@SpringBootTest
@DisplayName("TornApi构建测试")
class TornApiImplTest extends BaseWithoutSocketTest {
    @Resource
    private TornApiImpl tornApi;

    @Test
    @DisplayName("获取用户测试")
    void buildTest() {
        TornUserVO nullUser = tornApi.sendRequest(new TornUserDTO(123456789L), TornUserVO.class);
        Assertions.assertNull(nullUser);

        Long id = 3312605L;
        TornUserVO user = tornApi.sendRequest(new TornUserDTO(id), TornUserVO.class);
        Assertions.assertEquals(id, user.getPlayerId());
    }
}