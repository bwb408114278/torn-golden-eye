package pn.torn.goldeneye.msg.send;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;

/**
 * 获取群成员请求参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.031
 */
@AllArgsConstructor
public class GroupMemberReqParam implements BotHttpReqParam {
    /**
     * 群聊ID
     */
    private final long groupId;

    @Override
    public HttpMethod method() {
        return HttpMethod.POST;
    }

    @Override
    public String uri() {
        return "/get_group_member_list";
    }

    @Override
    public Object body() {
        return new GroupMemberBody(this.groupId, true);
    }

    @AllArgsConstructor
    @Getter
    private static class GroupMemberBody {
        /**
         * 群聊ID
         */
        @JsonProperty("group_id")
        private long groupId;
        /**
         * 是否启用缓存
         */
        @JsonProperty("no_cache")
        private boolean noCache;
    }
}