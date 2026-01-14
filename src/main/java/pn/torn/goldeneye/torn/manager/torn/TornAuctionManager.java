package pn.torn.goldeneye.torn.manager.torn;

import com.lark.oapi.service.bitable.v1.enums.AppTableCreateHeaderUiTypeEnum;
import com.lark.oapi.service.bitable.v1.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteBitTableProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
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
    private final SysSettingDAO settingDao;
    private final LarkSuiteProperty larkSuiteProperty;
    private static final String ARMOR_BONUS_NAME = "护甲特效名称";
    private static final String ARMOR_BONUS_VALUE = "护甲特效值";
    private static final String WEAPON_1_BONUS_NAME = "武器特效1名称";
    private static final String WEAPON_1_BONUS_VALUE = "武器特效1数值";
    private static final String WEAPON_2_BONUS_NAME = "武器特效2名称";
    private static final String WEAPON_2_BONUS_VALUE = "武器特效2数值";
    private static final String ARMOR_BONUS_FORMULA = "ifs(bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Impenetrable\",\"拦截\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%子弹伤害\"，bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Impregnable\",\"格挡\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%近战伤害\"，bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Invulnerable\",\"净化\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%负面状态\"，bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Imperviable\",\"提升\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%血量上限\"，bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Immutable\",\"提升\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%def属性\"，bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Irrepressible\",\"提升\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%dex属性\"，bitable::$table[#{tableId}].$field[#{bonusTitleId}]=\"Impassable\",bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%几率无敌\"，TRUE,\"\")";
    private static final String WEAPON_BONUS_FORMULA = "SWITCH(bitable::$table[#{tableId}].$field[#{bonusTitleId}], \n\"Achilles\", \"命中脚部时，增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\",\n\"Assassinate\", \"战斗的第一回合中，额外增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\",\n\"Backstab\", \"对手分心时，有\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率造成双倍伤害\",\n\"Berserk\", \"增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)，同时命中率降低一半\",\n\"Bleed\", \"使用该武器破防时，有\" &bitable::$table[#{tableId}].$field[#{bonusValueId}] &\"%几率触发流血效果：触发后第一回合追加45%伤害，然后每回合依次衰减，到第九回合为0%\",\n\"Blindside\", \"对手满血时，额外增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\",\n\"Bloodlust\", \"最终伤害的\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%转化为自身生命值\",\n\"Comeback\"，\"当自身生命低于1/4，额外增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\"，\n\"Conserve\"， \"降低\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%弹药消耗\"，\n\"Cripple\"，\"使用该武器破防时，有\" &bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率降低对手25%的Dex属性，可叠加三次\"，\n\"Crusher\"，\"命中头部，增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\"，\n\"Cupid\"，\"命中心脏，额外增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\"，\n\"Deadeye\"，\"暴击时，额外增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\"，\n\"Deadly\"，\"有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率额外增加500%伤害(加算)\"，\n\"Disarm\"，\"命中手或者胳膊时，会封印对手上一回合使用的武器\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"回合\"，\n\"Double-edged\"， \"有\" &bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率额造成双倍伤害并损失该伤害的1/4血\"，\n\"Double Tap\"，\"有\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率一回合内攻击两次\"，\n\"Empower\"，\"增加自身\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%力量属性\"，\n\"Eviscerate\"，\"破防对手后，下回合开始所有人对他造成的伤害增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%\"，\n\"Execute\"，\"当对手生命值低于\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%时，你的破防攻击可直接斩杀对手(对NPC无效)\"，\n\"Expose\"，\"增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%暴击概率\"，\n\"Finale\"，\"每个不使用该武器的回合，都将增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%武器伤害，可累积，使用后清空\"，\n\"Focus\"， \"当连续miss时，则每次miss增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%命中率，命中后清空\"，\n\"Frenzy\"， \"当连续命中时，则每次命中增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%命中率和伤害，miss后清空\"，\n\"Fury\"， bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率一回合内攻击两次\"，\n\"Grace\"，\"增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%命中率，同时伤害降低一半\"，\n\"Home Run\"，\"使用该武器的下一个回合，有\"&  bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率击飞对手扔来的临时武器\"，\n\"Irradiate\"， \"击杀对手后，会使对手获得1-3小时的辐射debuff\"，\n\"Motivation\"， \"使用该武器时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率使自己所有属性增加10%，最多叠加5次\"，\n\"Paralyzed\"，\"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率让对手瘫痪：300秒内的每个战斗回合有50%几率直接跳过\"，\n\"Parry\"， \"使用该武器的下一个回合，有\"&  bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率格挡对手的近战武器\"，\n\"Penetrate\"， \"命中时减少对手\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%护甲\"，\n\"Plunder\"，\"击杀对手后，可增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%mug金额\"，\n\"Powerful\"， \"额外增加\"&bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害\"，\n\"Proficience\"， \"击杀对手后，获得的经验值增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%\"，\n\"Puncture\"， \"命中时有\"&bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率忽视护甲\"，\n\"Quicken\"， \"命中时增加自身\"&bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%的speed属性\"，\n\"Rage\"， \"有\"&bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%的几率一回合内连续攻击2-8次\"，\n\"Revitalize\"， \"击杀对手后，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%的几率返还战斗消耗的能量\"，\n\"Roshambo\"， \"命中腹股沟时，额外增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\",\n\"Slow\"， \"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%降低对手25%的speed属性，可叠加3次\"，\n\"Smurf\"， \"当你比对手等级低时，则每低一级增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害\"，\n\"Specialist\"， \"额外增加\"&bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害，但该武器只能使用一个弹夹\"，\n\"Stricken\"， \"击杀对手后，增加对手\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%住院时间\"，\n\"Stun\"， \"使用该武器有\"&bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率使对手直接跳过下回合\"，\n\"Suppress\"， \"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率压制对手：使其后续每个战斗回合有25%几率直接跳过\"，\n\"Sure Shot\"，\"有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率必中\"，\n\"Throttle\"， \"命中喉部时，增加\" & bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\",\n\"Warlord\"， \"击杀对手后，增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%面子获得\"，\n\"Weaken\"， \"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率降低对手25%的Def属性，可叠加3次\"，\n\"Wind-up\"，\"蓄力一回合，下一回合增加\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%伤害(加算)\"，\n\"Wither\"， \"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率降低对手25%的力量属性，可叠加3次\",\n\"Blindfire\"， \"有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率在一回合内连续开枪直至打空所有弹药，触发后每次开枪的accuracy降低5.0\",\n\"Burn\"， \"破防时，有\" &bitable::$table[#{tableId}].$field[#{bonusValueId}] &\"%几率触发灼烧效果：触发后第一回合追加45%伤害，然后每回合依次衰减，到第三回合为0%\",\n\"Demoralize\"， \"有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率降低你所有对手10%的总属性，可叠加5次\",\n\"Emasculate\"， \"击杀对手后，增加自身\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%的happy\",\n\"Freeze\"， \"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率降低对手50%的speed或Dex属性\",\n\"Hazardous\"， \"造成伤害时，自身损失该伤害的\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%血\",\n\"Laceration\"， \"破防时，有\" &bitable::$table[#{tableId}].$field[#{bonusValueId}] &\"%几率触发撕裂效果：触发后第一回合追加90%伤害，然后每回合依次衰减，到第九回合为0%\",\n\"Poisoned\"， \"破防时，有\" &bitable::$table[#{tableId}].$field[#{bonusValueId}] &\"%几率触发中毒效果：触发后第一回合追加95%伤害，然后每回合依次衰减，到第十九回合为0%\",\n\"Shock\", \"破防时，有\"&bitable::$table[#{tableId}].$field[#{bonusValueId}]&\"%几率使对手跳过下一回合\",\n\"Smash\"， \"造成双倍伤害，但需充电一回合才能再次使用\",\n\"Spray\", \"弹夹填满时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率一枪打空所有弹药，造成双倍伤害\",\n\"Toxin\", \"破防时，有\"& bitable::$table[#{tableId}].$field[#{bonusValueId}] & \"%几率随机降低对手的一种战斗属性，可叠加3次\"\n)";

    /**
     * 上传拍卖行数据
     */
    public void uploadAuction(List<TornAuctionDO> auctionList) {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_AUCTION);
        larkSuiteApi.sendRequest(client -> {
            AppTableRecord[] param = new AppTableRecord[auctionList.size()];
            for (int i = 0; i < auctionList.size(); i++) {
                param[i] = parseDataParam(auctionList.get(i));
            }

            BatchCreateAppTableRecordReq req = BatchCreateAppTableRecordReq.newBuilder()
                    .appToken(bitTable.getAppToken())
                    .tableId(settingDao.querySettingValue(SettingConstants.LARK_AUCTION_TABLE_ID))
                    .batchCreateAppTableRecordReqBody(BatchCreateAppTableRecordReqBody.newBuilder()
                            .records(param).build())
                    .build();

            return client.bitable().v1().appTableRecord().batchCreate(req);
        });

        String rowsCount = settingDao.querySettingValue(SettingConstants.LARK_AUCTION_TABLE_ROWS_COUNT);
        int newCount = Integer.parseInt(rowsCount) + auctionList.size();
        settingDao.updateSetting(SettingConstants.LARK_AUCTION_TABLE_ROWS_COUNT, String.valueOf(newCount));
    }

    /**
     * 创建新的数据表
     */
    public void createNewTable(String appToken, TornAuctionDO auction) {
        CreateAppTableRespBody resp = larkSuiteApi.sendRequest(client -> {
            CreateAppTableReq req = CreateAppTableReq.newBuilder()
                    .appToken(appToken)
                    .createAppTableReqBody(CreateAppTableReqBody.newBuilder()
                            .table(ReqTable.newBuilder()
                                    .name("拍卖记录 - " + DateTimeUtils.convertToString(auction.getFinishTime()))
                                    .defaultViewName("拍卖记录总览")
                                    .fields(buildTableHeader())
                                    .build())
                            .build())
                    .build();
            return client.bitable().v1().appTable().create(req);
        });

        String tableId = resp.getTableId();
        ListAppTableFieldRespBody fieldResp = larkSuiteApi.sendRequest(client -> {
            ListAppTableFieldReq req = ListAppTableFieldReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .build();
            return client.bitable().v1().appTableField().list(req);
        });

        updateFormulaColumn(appToken, fieldResp.getItems(), tableId, ARMOR_BONUS_FORMULA, "护甲特效描述", ARMOR_BONUS_NAME, ARMOR_BONUS_VALUE);
        updateFormulaColumn(appToken, fieldResp.getItems(), tableId, WEAPON_BONUS_FORMULA, "武器特效1描述", WEAPON_1_BONUS_NAME, WEAPON_1_BONUS_VALUE);
        updateFormulaColumn(appToken, fieldResp.getItems(), tableId, WEAPON_BONUS_FORMULA, "武器特效2描述", WEAPON_2_BONUS_NAME, WEAPON_2_BONUS_VALUE);
        settingDao.updateSetting(SettingConstants.LARK_AUCTION_TABLE_ID, tableId);
        settingDao.updateSetting(SettingConstants.LARK_AUCTION_TABLE_ROWS_COUNT, String.valueOf(0));
    }

    /**
     * 更新公式列
     */
    private void updateFormulaColumn(String appToken, AppTableFieldForList[] fieldList, String tableId, String formula,
                                     String bonusDescName, String bonusTitleName, String bonusValueName) {
        String bonusDescId = "";
        String bonusTitleId = "";
        String bonusValueId = "";
        for (AppTableFieldForList field : fieldList) {
            if (!bonusDescId.isEmpty() && !bonusTitleId.isEmpty() && !bonusValueId.isEmpty()) {
                break;
            }

            if (bonusDescName.equals(field.getFieldName())) {
                bonusDescId = field.getFieldId();
            } else if (bonusTitleName.equals(field.getFieldName())) {
                bonusTitleId = field.getFieldId();
            } else if (bonusValueName.equals(field.getFieldName())) {
                bonusValueId = field.getFieldId();
            }
        }

        String formulaExpression = formula.replace("#{tableId}", tableId)
                .replace("#{bonusTitleId}", bonusTitleId)
                .replace("#{bonusValueId}", bonusValueId);
        String finalBonusDescId = bonusDescId;
        larkSuiteApi.sendRequest(client -> {
            UpdateAppTableFieldReq req = UpdateAppTableFieldReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .fieldId(finalBonusDescId)
                    .appTableField(AppTableField.newBuilder()
                            .fieldName(bonusDescName)
                            .type(20)
                            .property(AppTableFieldProperty.newBuilder()
                                    .formulaExpression(formulaExpression)
                                    .build())
                            .build())
                    .build();
            return client.bitable().v1().appTableField().update(req);
        });
    }

    /**
     * 转换表格数据参数
     */
    private AppTableRecord parseDataParam(TornAuctionDO auction) {
        TornItemsDO item = itemsManager.getMap().get(auction.getItemId());

        HashMap<String, Object> param = new HashMap<>();
        param.put("拍卖ID", auction.getId());
        param.put("名称", auction.getItemName());
        param.put("序列号", auction.getItemUid());
        param.put("稀有度", auction.getItemRarity());
        param.put("Q%", auction.getItemQuality());
        param.put("买方", auction.getBuyerName());
        param.put("买方ID", auction.getBuyerId());
        param.put("卖方", auction.getSellerName());
        param.put("卖方ID", auction.getSellerId());
        param.put("成交时间", DateTimeUtils.convertToTimestamp(auction.getFinishTime()));
        param.put("成交金额", auction.getPrice());
        param.put("竞价次数", auction.getBids());
        param.put("装备图片", item.getItemImage());

        if (TornConstants.ITEM_TYPE_WEAPON.equals(auction.getItemType())) {
            param.put("装备位置", item.getWeaponCategory());
            param.put("武器类型", auction.getItemSubType());
            param.put("武器伤害", auction.getItemDamage());
            param.put("武器命中", auction.getItemAccuracy());
            param.put("武器特效", auction.getBonuses().split(","));
            param.put(WEAPON_1_BONUS_NAME, auction.getBonus1Title());
            param.put(WEAPON_1_BONUS_VALUE, auction.getBonus1Value());
            param.put(WEAPON_2_BONUS_NAME, auction.getBonus2Title());
            param.put(WEAPON_2_BONUS_VALUE, auction.getBonus2Value());
        } else if ("Armor".equals(auction.getItemType())) {
            param.put("装备位置", "Defensive");
            param.put("护甲值", auction.getItemArmor());
            param.put(ARMOR_BONUS_NAME, auction.getBonus1Title());
            param.put(ARMOR_BONUS_VALUE, auction.getBonus1Value());
        }

        return AppTableRecord.newBuilder().fields(param).build();
    }

    /**
     * 构建表格的表头
     */
    private AppTableCreateHeader[] buildTableHeader() {
        return new AppTableCreateHeader[]{
                AppTableCreateHeader.newBuilder().fieldName("拍卖ID").type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("名称").type(1).build(),
                AppTableCreateHeader.newBuilder().fieldName("序列号").type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("装备位置").type(3).uiType(AppTableCreateHeaderUiTypeEnum.SINGLESELECT).property(AppTableFieldProperty.newBuilder().options(buildEquipTypeOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("武器类型").type(3).uiType(AppTableCreateHeaderUiTypeEnum.SINGLESELECT).property(AppTableFieldProperty.newBuilder().options(buildWeaponTypeOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("稀有度").type(3).uiType(AppTableCreateHeaderUiTypeEnum.SINGLESELECT).property(AppTableFieldProperty.newBuilder().options(buildRarityOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("Q%").type(2).property(AppTableFieldProperty.newBuilder().formatter("0.00").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("护甲值").type(2).property(AppTableFieldProperty.newBuilder().formatter("0.00").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName(ARMOR_BONUS_NAME).type(3).uiType(AppTableCreateHeaderUiTypeEnum.SINGLESELECT).property(AppTableFieldProperty.newBuilder().options(buildArmorBonusOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName(ARMOR_BONUS_VALUE).type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("护甲特效描述").type(20).property(AppTableFieldProperty.newBuilder().build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("武器伤害").type(2).property(AppTableFieldProperty.newBuilder().formatter("0.00").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("武器命中").type(2).property(AppTableFieldProperty.newBuilder().formatter("0.00").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("武器特效").type(4).uiType(AppTableCreateHeaderUiTypeEnum.MULTISELECT).property(AppTableFieldProperty.newBuilder().options(buildWeaponBonusOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName(WEAPON_1_BONUS_NAME).type(3).uiType(AppTableCreateHeaderUiTypeEnum.SINGLESELECT).property(AppTableFieldProperty.newBuilder().options(buildWeaponBonusOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName(WEAPON_1_BONUS_VALUE).type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("武器特效1描述").type(20).property(AppTableFieldProperty.newBuilder().build()).build(),
                AppTableCreateHeader.newBuilder().fieldName(WEAPON_2_BONUS_NAME).type(3).uiType(AppTableCreateHeaderUiTypeEnum.SINGLESELECT).property(AppTableFieldProperty.newBuilder().options(buildWeaponBonusOption()).build()).build(),
                AppTableCreateHeader.newBuilder().fieldName(WEAPON_2_BONUS_VALUE).type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("武器特效2描述").type(20).property(AppTableFieldProperty.newBuilder().build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("买方").type(1).build(),
                AppTableCreateHeader.newBuilder().fieldName("买方ID").type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("卖方").type(1).build(),
                AppTableCreateHeader.newBuilder().fieldName("卖方ID").type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("装备图片").type(1).build(),
                AppTableCreateHeader.newBuilder().fieldName("成交时间").type(5).uiType(AppTableCreateHeaderUiTypeEnum.DATETIME).property(AppTableFieldProperty.newBuilder().autoFill(false).dateFormatter("yyyy-MM-dd HH:mm").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("成交金额").type(2).property(AppTableFieldProperty.newBuilder().formatter("1,000").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("竞价次数").type(2).property(AppTableFieldProperty.newBuilder().formatter("0").build()).build(),
                AppTableCreateHeader.newBuilder().fieldName("图片").type(17).build(),
                AppTableCreateHeader.newBuilder().fieldName("创建时间").type(1001).uiType(AppTableCreateHeaderUiTypeEnum.CREATEDTIME).property(AppTableFieldProperty.newBuilder().dateFormatter("yyyy-MM-dd HH:mm").build()).build(),
        };
    }

    /**
     * 构建装备位置选项
     */
    private AppTableFieldPropertyOption[] buildEquipTypeOption() {
        return new AppTableFieldPropertyOption[]{
                AppTableFieldPropertyOption.newBuilder().name("Melee").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Primary").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Secondary").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Temporary").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Defensive").color(4).build()
        };
    }

    /**
     * 构建装备位置选项
     */
    private AppTableFieldPropertyOption[] buildWeaponTypeOption() {
        return new AppTableFieldPropertyOption[]{
                AppTableFieldPropertyOption.newBuilder().name("SMG").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Shotgun").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Rifle").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Machine Gun").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Heavy Artillery").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Pistol").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Piercing").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Mechanical").color(7).build(),
                AppTableFieldPropertyOption.newBuilder().name("Clubbing").color(8).build(),
                AppTableFieldPropertyOption.newBuilder().name("Slashing").color(9).build(),
                AppTableFieldPropertyOption.newBuilder().name("Machine gun").color(10).build(),
                AppTableFieldPropertyOption.newBuilder().name("Heavy artillery").color(0).build()
        };
    }

    /**
     * 构建稀有度选项
     */
    private AppTableFieldPropertyOption[] buildRarityOption() {
        return new AppTableFieldPropertyOption[]{
                AppTableFieldPropertyOption.newBuilder().name("Red").color(33).build(),
                AppTableFieldPropertyOption.newBuilder().name("Orange").color(12).build(),
                AppTableFieldPropertyOption.newBuilder().name("Yellow").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("None").color(0).build()
        };
    }

    /**
     * 构建护甲特效选项
     */
    private AppTableFieldPropertyOption[] buildArmorBonusOption() {
        return new AppTableFieldPropertyOption[]{
                AppTableFieldPropertyOption.newBuilder().name("Impenetrable").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Invulnerable").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Impregnable").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Insurmountable").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Impassable").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Irrepressible").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Immutable").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Imperviable").color(7).build()
        };
    }

    /**
     * 构建武器特效选项
     */
    private AppTableFieldPropertyOption[] buildWeaponBonusOption() {
        return new AppTableFieldPropertyOption[]{
                AppTableFieldPropertyOption.newBuilder().name("Achilles").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Assassinate").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Backstab").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Berserk").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Bleed").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Blindside").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Bloodlust").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Comeback").color(7).build(),
                AppTableFieldPropertyOption.newBuilder().name("Conserve").color(8).build(),
                AppTableFieldPropertyOption.newBuilder().name("Cripple").color(9).build(),
                AppTableFieldPropertyOption.newBuilder().name("Crusher").color(10).build(),
                AppTableFieldPropertyOption.newBuilder().name("Cupid").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Deadeye").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Deadly").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Disarm").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Double-edged").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Double Tap").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Empower").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Eviscerate").color(7).build(),
                AppTableFieldPropertyOption.newBuilder().name("Execute").color(8).build(),
                AppTableFieldPropertyOption.newBuilder().name("Expose").color(9).build(),
                AppTableFieldPropertyOption.newBuilder().name("Finale").color(10).build(),
                AppTableFieldPropertyOption.newBuilder().name("Focus").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Frenzy").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Fury").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Grace").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Home Run").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Irradiate").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Motivation").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Paralyzed").color(7).build(),
                AppTableFieldPropertyOption.newBuilder().name("Parry").color(8).build(),
                AppTableFieldPropertyOption.newBuilder().name("Penetrate").color(9).build(),
                AppTableFieldPropertyOption.newBuilder().name("Plunder").color(10).build(),
                AppTableFieldPropertyOption.newBuilder().name("Powerful").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Proficience").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Puncture").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Quicken").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Rage").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Revitalize").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Roshambo").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Slow").color(7).build(),
                AppTableFieldPropertyOption.newBuilder().name("Smurf").color(8).build(),
                AppTableFieldPropertyOption.newBuilder().name("Specialist").color(9).build(),
                AppTableFieldPropertyOption.newBuilder().name("Stricken").color(10).build(),
                AppTableFieldPropertyOption.newBuilder().name("Stun").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Suppress").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Sure Shot").color(2).build(),
                AppTableFieldPropertyOption.newBuilder().name("Throttle").color(3).build(),
                AppTableFieldPropertyOption.newBuilder().name("Warlord").color(4).build(),
                AppTableFieldPropertyOption.newBuilder().name("Weaken").color(5).build(),
                AppTableFieldPropertyOption.newBuilder().name("Wind-up").color(6).build(),
                AppTableFieldPropertyOption.newBuilder().name("Wither").color(7).build(),
                AppTableFieldPropertyOption.newBuilder().name("Shock").color(8).build(),
                AppTableFieldPropertyOption.newBuilder().name("Home run").color(9).build(),
                AppTableFieldPropertyOption.newBuilder().name("Demoralize").color(10).build(),
                AppTableFieldPropertyOption.newBuilder().name("Freeze").color(0).build(),
                AppTableFieldPropertyOption.newBuilder().name("Blindfire").color(1).build(),
                AppTableFieldPropertyOption.newBuilder().name("Toxin").color(2).build()
        };
    }
}