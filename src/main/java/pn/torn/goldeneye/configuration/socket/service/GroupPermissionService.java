package pn.torn.goldeneye.configuration.socket.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 群聊权限逻辑层
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.05.20
 */
@Component
@RequiredArgsConstructor
public class GroupPermissionService {
    private final ProjectProperty projectProperty;

    /**
     * 校验管理员权限
     *
     * @return true 表示没有权限
     */
    public boolean invalidAdmin(long userId, BaseGroupMsgStrategy strategy, TornFactionBO faction) {
        if (projectProperty.getAdminId().contains(userId)) {
            return false;
        } else if (strategy.isNeedSa()) {
            return true;
        }

        if (strategy.getRoleType() == null) {
            return false;
        }

        // Leader 默认有权限
        List<Long> leaderList = faction != null
                ? NumberUtils.splitToLongList(faction.getGroupAdminIds())
                : List.of();
        if (leaderList.contains(userId)) {
            return false;
        }

        if (TornFactionRoleTypeEnum.OC_COMMANDER.equals(strategy.getRoleType())) {
            List<Long> ocCommanderList = faction != null
                    ? NumberUtils.splitToLongList(faction.getOcCommanderIds())
                    : List.of();
            return !ocCommanderList.contains(userId);
        }

        if (TornFactionRoleTypeEnum.WAR_COMMANDER.equals(strategy.getRoleType())) {
            List<Long> warCommanderList = faction != null
                    ? NumberUtils.splitToLongList(faction.getWarCommanderIds())
                    : List.of();
            return !warCommanderList.contains(userId);
        }

        if (TornFactionRoleTypeEnum.QUARTERMASTER.equals(strategy.getRoleType())) {
            List<Long> quartermasterList = faction != null
                    ? NumberUtils.splitToLongList(faction.getQuartermasterIds())
                    : List.of();
            return !quartermasterList.contains(userId);
        }

        return true;
    }
}