package pn.torn.goldeneye.msg;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.TestProperty;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.AtGroupMsg;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;

/**
 * 群聊消息构建器测试
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@SpringBootTest
@DisplayName("群聊消息测试")
class GroupMsgHttpBuilderTest {
    @Resource
    private Bot bot;
    @Resource
    private TestProperty testProperty;

    @Test
    @DisplayName("构建消息测试")
    void buildTest() {
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(testProperty.getGroupId())
                .addMsg(new TextGroupMsg("单元测试代码"))
                .addMsg(new AtGroupMsg(408114278L))
                .build();
        bot.sendRequest(param, Void.class);
    }
}