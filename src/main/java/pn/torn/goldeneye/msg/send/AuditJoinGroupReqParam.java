package pn.torn.goldeneye.msg.send;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;

/**
 * 审批加入群聊请求参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@AllArgsConstructor
public class AuditJoinGroupReqParam implements BotHttpReqParam {
    /**
     * 请求ID
     */
    private long reqId;

    @Override
    public HttpMethod method() {
        return HttpMethod.POST;
    }

    @Override
    public String uri() {
        return "/set_group_add_request";
    }

    @Override
    public Object body() {
        return new GroupSysBody(String.valueOf(reqId), true);
    }

    @AllArgsConstructor
    @Getter
    private static class GroupSysBody {
        /**
         * 请求Flag
         */
        private String flag;
        /**
         * 是否同意
         */
        private Boolean approve;
    }
}