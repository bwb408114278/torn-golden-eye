package pn.torn.goldeneye.msg.send.param;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;

/**
 * 踢出群聊请求参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@AllArgsConstructor
public class KickGroupMemberReqParam implements BotHttpReqParam {
    /**
     * 群号
     */
    private long groupId;
    /**
     * QQ号
     */
    private long qqId;

    @Override
    public HttpMethod method() {
        return HttpMethod.POST;
    }

    @Override
    public String uri() {
        return "/set_group_kick";
    }

    @Override
    public Object body() {
        return new KickGroupMemberBody(String.valueOf(groupId), String.valueOf(qqId), false);
    }

    @AllArgsConstructor
    @Getter
    private static class KickGroupMemberBody {
        /**
         * 请求Flag
         */
        @JsonProperty("group_id")
        private String groupId;
        /**
         * 是否同意
         */
        @JsonProperty("user_id")
        private String userId;
        /**
         * 是否拒绝加群请求
         */
        @JsonProperty("reject_add_request")
        private Boolean rejectAddRequest;
    }
}