package pn.torn.goldeneye.msg.send;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import pn.torn.goldeneye.BaseWithoutSocketTest;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.TestProperty;

/**
 * 获取群聊成员测试
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.31
 */
@SpringBootTest
@DisplayName("获取群聊成员测试")
class GroupMemberReqParamTest extends BaseWithoutSocketTest {
    @Resource
    private Bot bot;
    @Resource
    private TestProperty testProperty;

    @Test
    @DisplayName("获取群聊成员测试")
    void buildTest() {
        BotHttpReqParam param = new GroupMemberReqParam(testProperty.getGroupId());
        ResponseEntity<String> str = bot.sendRequest(param, String.class);
        System.out.println(str.getBody());
    }
}