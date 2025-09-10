package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OC收益查询实现类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.20
 */
@Component
@RequiredArgsConstructor
public class OcBenefitQueryStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcBenefitDAO benefitDao;
    private final TornUserDAO userDao;
    private final TornSettingOcDAO settingOcDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_BENEFIT;
    }

    @Override
    public String getCommandDescription() {
        return "获取当月OC收益，例g#" + BotCommands.OC_BENEFIT + "(#用户ID)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long userId;
        try {
            userId = super.getTornUserId(sender, msg);
        } catch (BizException e) {
            return super.buildTextMsg(e.getMsg());
        }

        if (userId == 0L) {
            return super.buildTextMsg("金蝶不认识TA哦，看看群名片对不对");
        }

        LocalDateTime fromDate = LocalDate.now().minusDays(LocalDate.now().getDayOfMonth() - 1L)
                .atTime(0, 0, 0);
        List<TornFactionOcBenefitDO> benefitList = benefitDao.lambdaQuery()
                .eq(TornFactionOcBenefitDO::getUserId, userId)
                .between(TornFactionOcBenefitDO::getOcFinishTime, fromDate, LocalDateTime.now())
                .orderByDesc(TornFactionOcBenefitDO::getOcFinishTime)
                .list();
        if (benefitList.isEmpty()) {
            return super.buildTextMsg("暂未查询到" + LocalDate.now().getMonthValue() + "月完成的OC");
        }

        return super.buildImageMsg(buildDetailMsg(userId, benefitList));
    }

    /**
     * 构建OC收益表格
     */
    private String buildDetailMsg(long userId, List<TornFactionOcBenefitDO> benefitList) {
        TornUserDO user = userDao.getById(userId);
        Map<String, TornSettingOcDO> ocMap = settingOcDao.getNameMap();

        DecimalFormat formatter = new DecimalFormat("#,###");
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(user.getNickname() + "  " + LocalDate.now().getMonthValue() + "月OC收益",
                "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 7);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("OC名称", "等级", "状态", "完成时间", "坑位", "成功率", "收益"));
        tableConfig.setSubTitle(1, 7);

        for (int i = 0; i < benefitList.size(); i++) {
            tableConfig.setCellStyle(i + 2, 6, new TableImageUtils.CellStyle().setHorizontalPadding(20));

            TornFactionOcBenefitDO benefit = benefitList.get(i);
            tableData.add(List.of(
                    benefit.getOcName(),
                    ocMap.get(benefit.getOcName()).getRank().toString(),
                    benefit.getOcStatus(),
                    DateTimeUtils.convertToString(benefit.getOcFinishTime()),
                    benefit.getUserPosition(),
                    benefit.getUserPassRate().toString(),
                    formatter.format(benefit.getBenefitMoney())));
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}