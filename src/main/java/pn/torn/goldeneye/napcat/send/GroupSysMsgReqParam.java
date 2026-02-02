package pn.torn.goldeneye.napcat.send;

import lombok.Getter;
import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;

/**
 * 获取群系统消息请求参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
public class GroupSysMsgReqParam implements BotHttpReqParam {
    @Override
    public HttpMethod method() {
        return HttpMethod.POST;
    }

    @Override
    public String uri() {
        return "/get_group_system_msg";
    }

    @Override
    public Object body() {
        return new GroupSysBody();
    }

    @Getter
    private static class GroupSysBody {
        private final String ignore = null;
    }
}