package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.faction.oc.income.TornOcBatchIncomeService;

import java.util.List;

/**
 * 获取当前任务策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TestStrategyImpl extends BaseGroupMsgStrategy {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornOcBatchIncomeService ocBatchIncomeService;

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public String getCommand() {
        return "测试";
    }

    @Override
    public String getCommandDescription() {
        return "";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        virtualThreadExecutor.execute(ocBatchIncomeService::batchCalculateIncome);
        return super.buildTextMsg("收益生成中，稍后查看日志和数据");
    }
}