package pn.torn.goldeneye.msg.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 获取用户策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class GetUserStrategyImpl extends ManageMsgStrategy {
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return "查询用户";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        if (!NumberUtils.isLong(msg)) {
            return super.sendErrorFormatMsg();
        }

        TornUserDO user = userDao.getById(Long.parseLong(msg));
        if (user == null) {
            return super.buildTextMsg("未获取到用户信息，请先执行同步用户");
        }

        return super.buildTextMsg("用户信息:" +
                "\nID:" + user.getId() +
                "\n昵称:" + user.getNickname() +
                "\nAge:" + Math.abs(ChronoUnit.DAYS.between(LocalDateTime.now(), user.getRegisterTime())));
    }
}