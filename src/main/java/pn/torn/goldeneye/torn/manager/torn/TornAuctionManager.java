package pn.torn.goldeneye.torn.manager.torn;

import com.lark.oapi.service.bitable.v1.model.AppTableRecord;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordReqBody;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteBitTableProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.HashMap;
import java.util.List;

/**
 * Torn拍卖行上传数据公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Component
@RequiredArgsConstructor
public class TornAuctionManager {
    private final LarkSuiteApi larkSuiteApi;
    private final TornItemsManager itemsManager;
    private final LarkSuiteProperty larkSuiteProperty;

    /**
     * 上传拍卖行数据
     */
    public void uploadAuction(List<TornAuctionDO> auctionList) {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_AUCTION);
        larkSuiteApi.sendRequest(client -> {
            AppTableRecord[] param = new AppTableRecord[auctionList.size()];
            for (int i = 0; i < auctionList.size(); i++) {
                param[i] = parseParam(auctionList.get(i));
            }

            BatchCreateAppTableRecordReq req = BatchCreateAppTableRecordReq.newBuilder()
                    .appToken(bitTable.getAppToken())
                    .tableId(bitTable.getTableId())
                    .batchCreateAppTableRecordReqBody(BatchCreateAppTableRecordReqBody.newBuilder()
                            .records(param).build())
                    .build();

            return client.bitable().v1().appTableRecord().batchCreate(req);
        });
    }

    private AppTableRecord parseParam(TornAuctionDO auction) {
        TornItemsDO item = itemsManager.getMap().get(auction.getItemId());

        HashMap<String, Object> param = new HashMap<>();
        param.put("名称", auction.getItemName());
        param.put("序列号", auction.getItemUid());
        param.put("稀有度", auction.getItemRarity());
        param.put("Q%", auction.getItemQuality());
        param.put("买方", auction.getBuyerName());
        param.put("买方ID", auction.getBuyerId());
        param.put("卖方", auction.getSellerName());
        param.put("卖方ID", auction.getSellerId());
        param.put("成交时间", DateTimeUtils.convertToShortTimestamp(auction.getFinishTime()));
        param.put("成交金额", auction.getPrice());
        param.put("竞价次数", auction.getBids());
        param.put("装备图片", item.getItemImage());

        if (TornConstants.ITEM_TYPE_WEAPON.equals(auction.getItemType())) {
            param.put("装备位置", item.getWeaponCategory());
            param.put("武器类型", auction.getItemSubType());
            param.put("武器伤害", auction.getItemDamage());
            param.put("武器命中", auction.getItemAccuracy());
            param.put("武器特效", auction.getBonuses().split(","));
            param.put("武器特效1名称", auction.getBonus1Title());
            param.put("武器特效1数值", auction.getBonus1Value());
            param.put("武器特效2名称", auction.getBonus2Title());
            param.put("武器特效2数值", auction.getBonus2Value());
        } else if ("Armor".equals(auction.getItemType())) {
            param.put("装备位置", "Defensive");
            param.put("护甲值", auction.getItemArmor());
            param.put("护甲特效名称", auction.getBonus1Title());
            param.put("护甲特效值", auction.getBonus1Value());
        }

        return AppTableRecord.newBuilder().fields(param).build();
    }
}