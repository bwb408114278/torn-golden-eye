package pn.torn.goldeneye.napcat.strategy.vip;

import jakarta.annotation.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseVipMsgStrategy;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeConfigDAO;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeStateDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 设置VIP提醒的基类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.14
 */
public abstract class BaseVipNoticeConfigStrategyImpl extends BaseVipMsgStrategy {
    @Resource
    private VipNoticeConfigDAO configDao;
    @Resource
    private VipNoticeStateDAO stateDao;

    @Override
    protected List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg) {
        if (!StringUtils.hasText(msg)) {
            return List.of(new TextQqMsg(buildSupportTypeMsg()));
        }

        String[] typeArray = msg.split(",");
        if (typeArray.length < 1) {
            return List.of(new TextQqMsg(buildSupportTypeMsg()));
        }

        List<VipNoticeTypeEnum> typeList = Arrays.stream(typeArray)
                .map(VipNoticeTypeEnum::aliasOf)
                .filter(Objects::nonNull)
                .toList();
        if (CollectionUtils.isEmpty(typeList)) {
            return List.of(new TextQqMsg(buildSupportTypeMsg()));
        }

        VipNoticeConfigDO config = configDao.getOrCreate(user);
        int changeEnableTypes = 0;
        for (VipNoticeTypeEnum type : typeList) {
            if (needChange(config, type)) {
                changeEnableTypes += handleChangeType(type);
            }
        }

        configDao.lambdaUpdate()
                .set(VipNoticeConfigDO::getEnableTypes, config.getEnableTypes() + changeEnableTypes)
                .eq(VipNoticeConfigDO::getId, config.getId())
                .update();

        List<VipNoticeStateDO> stateList = stateDao.lambdaQuery().eq(VipNoticeStateDO::getUserId, user.getId()).list();
        for (VipNoticeTypeEnum type : typeList) {
            if (stateList.stream().noneMatch(s -> s.getNoticeType().equals(type.getBit()))) {
                stateDao.save(new VipNoticeStateDO(user.getId(), type));
            }
        }

        return List.of(new TextQqMsg("设置成功, 可以在提醒群内接收消息了"));
    }

    private String buildSupportTypeMsg() {
        List<String> typeAliasList = Arrays.stream(VipNoticeTypeEnum.values()).map(VipNoticeTypeEnum::getAlias).toList();
        String enableTypeMsg = String.join(", ", typeAliasList);
        return getSubCommandDesc() + ", 一次设置多个可以用英文逗号,分隔\n" +
                "例如g#" + getCommand() + "#药,Booster" +
                "\n目前支持的类型如下：" + enableTypeMsg;
    }

    /**
     * 获取子命令描述
     *
     * @return 子命令描述
     */
    protected abstract String getSubCommandDesc();

    /**
     * 是否需要修改
     *
     * @param config 当前配置
     * @param type   需要修改的类型
     * @return true为是
     */
    protected abstract boolean needChange(VipNoticeConfigDO config, VipNoticeTypeEnum type);

    /**
     * 处理需要修改的类型
     *
     * @param type 要修改的类型
     * @return 要修改的位掩码
     */
    protected abstract int handleChangeType(VipNoticeTypeEnum type);
}