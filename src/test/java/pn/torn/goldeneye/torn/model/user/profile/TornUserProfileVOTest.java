package pn.torn.goldeneye.torn.model.user.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Torn用户详情响应解析测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@DisplayName("Torn用户详情响应解析测试")
class TornUserProfileVOTest {
    /**
     * 取自 /v2/user/{id}/profile 的响应报文结构（signed_up 为秒级时间戳）
     */
    private static final String PROFILE_JSON = """
            {"profile":{"id":2554043,"name":"Foo","signed_up":1767225600,"faction_id":20465,
            "status":{"description":"Okay","state":"Okay"},
            "last_action":{"status":"Online","timestamp":1767225600,"relative":"0 minutes ago"},
            "life":{"current":100,"maximum":100}}}""";

    @Test
    @DisplayName("signed_up按snake_case映射并转换为注册时间")
    void jsonToObj_shouldMapSignedUpToRegisterTime() {
        TornUserVO resp = JsonUtils.jsonToObj(PROFILE_JSON, TornUserVO.class);

        assertNotNull(resp.getProfile());
        TornUserDO user = resp.getProfile().convert2DO();
        assertEquals(2554043L, user.getId());
        assertEquals("Foo", user.getNickname());
        assertEquals(20465L, user.getFactionId());
        // 1767225600 = 2026-01-01T00:00:00Z，按项目约定 +8 小时落库为北京时间
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0, 0), user.getRegisterTime());
    }
}
