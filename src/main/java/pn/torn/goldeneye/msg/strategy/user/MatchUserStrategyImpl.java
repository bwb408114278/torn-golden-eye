package pn.torn.goldeneye.msg.strategy.user;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.utils.NumberUtils;

/**
 * 匹配用户策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
public class MatchUserStrategyImpl extends ManageMsgStrategy {
    @Override
    public String getCommand() {
        return "同步用户";
    }

    @Override
    public void handle(String msg) {
        if (!NumberUtils.isLong(msg)) {
            super.sendErrorFormatMsg();
        }
    }
}