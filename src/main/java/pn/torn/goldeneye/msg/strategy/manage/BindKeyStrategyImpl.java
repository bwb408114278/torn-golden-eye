package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.key.TornKeyTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BasePrivateMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyDTO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyVO;
import pn.torn.goldeneye.torn.service.data.TornUserDataService;
import pn.torn.goldeneye.torn.service.user.TornUserService;

import java.util.List;

/**
 * 绑定Torn Api Key策略实现
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.21
 */
@Component
@RequiredArgsConstructor
public class BindKeyStrategyImpl extends BasePrivateMsgStrategy {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornUserService userService;
    private final TornUserDataService userDataService;

    @Override
    public String getCommand() {
        return BotCommands.BIND_KEY;
    }

    @Override
    public String getCommandDescription() {
        return "私聊金眼g#" + BotCommands.BIND_KEY + "#Key，成功金眼会回复消息";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg("消息格式不正确");
        }

        TornApiKeyVO key = tornApi.sendRequest(new TornApiKeyDTO(msg), null, TornApiKeyVO.class);
        if (key == null) {
            return super.buildTextMsg("请求Torn Api失败，请确认Key是否正确");
        }

        TornKeyTypeEnum keyType = TornKeyTypeEnum.codeOf(key.getInfo().getAccess().getType());
        if (keyType == null || (!TornKeyTypeEnum.LIMIT.equals(keyType) && !TornKeyTypeEnum.FULL.equals(keyType))) {
            return super.buildTextMsg("需要Limited级别的Key才能支持金眼使用");
        }

        TornApiKeyDO keyData = new TornApiKeyDO(sender.getUserId(), msg, key.getInfo(), keyType);
        List<TornApiKeyDO> allKeyList = apiKeyConfig.getAllEnableKeys();
        TornApiKeyDO oldKey = allKeyList.stream()
                .filter(s -> s.getUserId().equals(key.getInfo().getUser().getId()))
                .findAny().orElse(null);
        if (oldKey == null) {
            apiKeyConfig.addApiKey(keyData);
            virtualThreadExecutor.execute(() -> {
                userService.updateUserData(keyData);
                userDataService.spiderData(keyData, List.of());
            });
            return super.buildTextMsg(keyData.getUserId() + "绑定" + keyData.getKeyLevel() + "级别的Key成功");
        }

        TornApiKeyDO sameKey = allKeyList.stream().filter(s -> s.getApiKey().equals(msg)).findAny().orElse(null);
        if (sameKey != null) {
            return super.buildTextMsg("已存在相同的Key");
        }

        apiKeyConfig.updateApiKey(oldKey, keyData);
        return super.buildTextMsg(keyData.getUserId() + "替换" + keyData.getKeyLevel() + "级别的Key成功");
    }
}