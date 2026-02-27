package pn.torn.goldeneye.napcat.send;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;

/**
 * 撤回消息请求参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@AllArgsConstructor
public class DeleteMsgReqParam implements BotHttpReqParam {
    /**
     * 消息ID
     */
    private long msgId;

    @Override
    public HttpMethod method() {
        return HttpMethod.POST;
    }

    @Override
    public String uri() {
        return "/delete_msg";
    }

    @Override
    public Object body() {
        return new DeleteMsgBody(String.valueOf(msgId));
    }

    private record DeleteMsgBody(@JsonProperty("message_id") String messageId) {
    }
}