package pn.torn.goldeneye.msg;

import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.BotReqParam;
import pn.torn.goldeneye.msg.param.GroupMsgParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 群聊消息构建器
 *
 * @author Bai
 * @version 1.0
 * @since 2025.06.22
 */
public class GroupMsgBuilder {
    private static final String URI_GROUP_MSG = "/send_group_msg";

    /**
     * 请求参数列表
     */
    private final List<GroupMsgParam<?>> paramList = new ArrayList<>();
    private long groupId = 0L;

    /**
     * 设置群号
     */
    public GroupMsgBuilder setGroupId(long groupId) {
        this.groupId = groupId;
        return this;
    }

    /**
     * 添加消息
     */
    public GroupMsgBuilder addMsg(GroupMsgParam<?> param) {
        this.paramList.add(param);
        return this;
    }

    public BotReqParam build() {
        return new BotReqParam() {
            @Override
            public HttpMethod method() {
                return HttpMethod.POST;
            }

            @Override
            public String uri() {
                return URI_GROUP_MSG;
            }

            @Override
            public Object body() {
                return new GroupMsgReqParam(groupId, paramList);
            }
        };
    }
}