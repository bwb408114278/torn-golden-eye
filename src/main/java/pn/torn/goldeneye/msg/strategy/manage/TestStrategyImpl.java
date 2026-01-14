package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteBitTableProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornAuctionDAO;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;
import pn.torn.goldeneye.torn.manager.torn.TornAuctionManager;

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
    private final TornAuctionManager auctionManager;
    private final TornAuctionDAO auctionDao;
    private final LarkSuiteProperty larkSuiteProperty;

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public String getCommand() {
        return "测试";
    }

    @Override
    public String getCommandDescription() {
        return "测试";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_AUCTION);
        TornAuctionDO auction = auctionDao.getById(473640);
        auctionManager.createNewTable(bitTable.getAppToken(), auction);

        return super.buildTextMsg("创建新表格成功, 请打开飞书查看");
    }
}