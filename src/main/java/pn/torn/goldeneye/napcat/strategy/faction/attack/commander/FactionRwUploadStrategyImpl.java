package pn.torn.goldeneye.napcat.strategy.faction.attack.commander;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.torn.manager.faction.attack.TornRwUploadManager;

import java.util.List;

/**
 * RW对冲战斗统计策略实现类
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.10
 */
@Component
@RequiredArgsConstructor
public class FactionRwUploadStrategyImpl extends BaseRwStrategy {
    private final TornRwUploadManager rwUploadManager;

    @Override
    public String getCommand() {
        return BotCommands.RW_UPLOAD;
    }

    @Override
    public String getCommandDescription() {
        return "上传RW对冲结果到飞书";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.LEADER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        List<PlayerAttackStatDO> attackList = super.queryAttackList(rw);
        if (CollectionUtils.isEmpty(attackList)) {
            return super.buildTextMsg("未查询到战斗记录");
        }

        rwUploadManager.uploadRwData(rw, attackList);
        return super.buildTextMsg("上传成功, sheetId为" + rw.getLarksuiteSheetId()
                + "\n查看数据地址: https://my.feishu.cn/sheets/H8yPspU4yhIuuotAlqlcyCETnuh?from=from_copylink&sheet="
                + rw.getLarksuiteSheetId());
    }
}