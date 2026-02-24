package pn.torn.goldeneye.torn.model.user.log;

import lombok.Data;
import pn.torn.goldeneye.repository.model.vip.VipPayRecordDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Torn用户日志列表响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Data
public class TornUserLogListVO {
    /**
     * 日志ID
     */
    private String id;
    /**
     * 日志时间
     */
    private long timestamp;
    /**
     * 日志数据
     */
    private TornUserLogDataVO data;

    public List<VipPayRecordDO> convert2DO(TornUserManager userManager) {
        TornUserDO user = userManager.getUserById(this.data.getSender());
        Long qqId = user == null ? null : user.getQqId();
        LocalDateTime logTime = DateTimeUtils.convertToDateTime(this.timestamp);

        List<VipPayRecordDO> resultList = new ArrayList<>();
        for (TornUserLogItemVO item : this.data.getItems()) {
            VipPayRecordDO pay = new VipPayRecordDO();
            pay.setLogId(this.id);
            pay.setUserId(this.data.getSender());
            pay.setQqId(qqId);
            pay.setItemId(item.getId());
            pay.setItemQty(item.getQty());
            pay.setRemainQty(item.getQty());
            pay.setLogTime(logTime);

            resultList.add(pay);
        }

        return resultList;
    }
}
