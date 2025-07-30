package pn.torn.goldeneye.msg.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 匹配用户策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class MatchUserStrategyImpl extends ManageMsgStrategy {
    private final TornApi tornApi;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return "同步用户";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        if (!NumberUtils.isLong(msg)) {
            return super.sendErrorFormatMsg();
        }

        Long id = Long.parseLong(msg);
        TornUserVO user = tornApi.sendRequest(new TornUserDTO(id), TornUserVO.class);
        if (user == null) {
            return super.buildTextMsg("未查询到用户");
        }

        TornUserDO oldData = userDao.getById(id);
        TornUserDO newData = user.convert2DO();
        if (oldData == null) {
            userDao.save(newData);
        } else {
            userDao.updateById(newData);
        }
        return super.buildTextMsg("更新用户" + id + "成功");
    }
}