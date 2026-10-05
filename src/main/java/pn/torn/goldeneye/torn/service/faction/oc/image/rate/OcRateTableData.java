package pn.torn.goldeneye.torn.service.faction.oc.image.rate;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

import java.util.List;
import java.util.Objects;

/**
 * 用户OC成功率表组装输入。
 *
 * @param user         目标成员，取其昵称作为图片标题
 * @param ocList       已过滤、已按展示顺序（级别降序、名称升序）排列的OC清单
 * @param allSlotList  全量岗位设置
 * @param ocUserList   目标成员的全量OC成功率记录
 * @param factionSlots 目标成员所在帮派的岗位要求覆盖，调用方已按帮派ID过滤
 * @author Bai
 * @version 1.9.0
 * @since 2026.10.05
 */
public record OcRateTableData(TornUserDO user,
                              List<TornSettingOcDO> ocList,
                              List<TornSettingOcSlotDO> allSlotList,
                              List<TornFactionOcUserDO> ocUserList,
                              List<TornSettingFactionOcSlotDO> factionSlots) {

    /**
     * 创建并校验组装输入，同时防御性复制各集合。
     */
    public OcRateTableData {
        Objects.requireNonNull(user, "user不能为null");
        Objects.requireNonNull(ocList, "ocList不能为null");
        Objects.requireNonNull(allSlotList, "allSlotList不能为null");
        Objects.requireNonNull(ocUserList, "ocUserList不能为null");
        Objects.requireNonNull(factionSlots, "factionSlots不能为null");
        ocList = List.copyOf(ocList);
        allSlotList = List.copyOf(allSlotList);
        ocUserList = List.copyOf(ocUserList);
        factionSlots = List.copyOf(factionSlots);
    }
}
