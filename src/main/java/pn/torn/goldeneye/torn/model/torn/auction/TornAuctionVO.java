package pn.torn.goldeneye.torn.model.torn.auction;

import lombok.Data;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.utils.CharacterUtils;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * Torn拍卖响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
public class TornAuctionVO {
    /**
     * 拍卖ID
     */
    private long id;
    /**
     * 卖方
     */
    private TornAuctionUserVO seller;
    /**
     * 买方
     */
    private TornAuctionUserVO buyer;
    /**
     * 拍卖完成时间
     */
    private long timestamp;
    /**
     * 价格
     */
    private long price;
    /**
     * 竞价次数
     */
    private int bids;
    /**
     * 物品详情
     */
    private TornAuctionItemVO item;

    public TornAuctionDO convert2DO(TornItemsManager itemsManager) {
        TornAuctionDO auction = new TornAuctionDO();
        auction.setId(this.id);
        auction.setBuyerId(this.buyer.getId());
        auction.setBuyerName(this.buyer.getName());
        auction.setSellerId(this.seller.getId());
        auction.setSellerName(this.seller.getName());
        auction.setFinishTime(DateTimeUtils.convertToDateTime(this.timestamp));
        auction.setPrice(this.price);
        auction.setBids(this.bids);
        auction.setItemId(this.item.getId());
        auction.setItemName(this.item.getName());
        auction.setItemUid(this.item.getUid());
        auction.setItemType(this.item.getType());
        auction.setItemSubType(this.item.getSubType());
        auction.setWeaponCategory(TornConstants.ITEM_TYPE_WEAPON.equals(this.item.getType()) ?
                itemsManager.getMap().get(auction.getItemId()).getWeaponCategory() : null);

        auction.setItemRarity(StringUtils.hasText(this.item.getRarity()) ?
                CharacterUtils.capitalFirstLetter(this.item.getRarity())
                : "None");

        if (this.item.getStats() != null) {
            auction.setItemDamage(this.item.getStats().getDamage());
            auction.setItemAccuracy(this.item.getStats().getAccuracy());
            auction.setItemArmor(this.item.getStats().getArmor());
            auction.setItemQuality(this.item.getStats().getQuality());
        }

        if (!CollectionUtils.isEmpty(this.item.getBonuses())) {
            String bonuses = this.item.getBonuses().getFirst().getTitle();

            auction.setBonus1Id(this.item.getBonuses().getFirst().getId());
            auction.setBonus1Title(this.item.getBonuses().getFirst().getTitle());
            auction.setBonus1Value(this.item.getBonuses().getFirst().getValue());
            auction.setBonus1Desc(this.item.getBonuses().getFirst().getDescription());

            if (this.item.getBonuses().size() > 2) {
                throw new BizException("ID为" + this.id + "的拍卖，加成超过2个!");
            } else if (this.item.getBonuses().size() == 2) {
                bonuses = this.item.getBonuses().getFirst().getTitle() + "," + this.item.getBonuses().getLast().getTitle();

                auction.setBonus2Id(this.item.getBonuses().getLast().getId());
                auction.setBonus2Title(this.item.getBonuses().getLast().getTitle());
                auction.setBonus2Value(this.item.getBonuses().getLast().getValue());
                auction.setBonus2Desc(this.item.getBonuses().getLast().getDescription());
            }

            auction.setBonuses(bonuses);
        }

        return auction;
    }
}