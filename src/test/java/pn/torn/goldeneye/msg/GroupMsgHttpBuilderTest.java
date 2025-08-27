package pn.torn.goldeneye.msg;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.BaseWithoutSocketTest;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.AtQqMsg;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;

/**
 * 群聊消息构建器测试
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@SpringBootTest
@DisplayName("群聊消息测试")
class GroupMsgHttpBuilderTest extends BaseWithoutSocketTest {
    @Resource
    private Bot bot;
    @Resource
    private ProjectProperty projectProperty;

    @Test
    @DisplayName("构建消息测试")
    void buildTest() {
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getGroupId())
                .addMsg(new TextQqMsg("单元测试代码"))
                .addMsg(new AtQqMsg(408114278L))
                .build();
        bot.sendRequest(param, Void.class);
    }
}