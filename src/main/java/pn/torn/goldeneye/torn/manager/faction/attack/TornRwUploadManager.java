package pn.torn.goldeneye.torn.manager.faction.attack;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenReq;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenReqBody;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenResp;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.larksuite.sheet.LarkSuiteAddSheetDTO;
import pn.torn.goldeneye.base.larksuite.sheet.LarkSuiteSheetDataVO;
import pn.torn.goldeneye.base.larksuite.sheet.LarkSuiteSheetVO;
import pn.torn.goldeneye.base.larksuite.sheet.data.LarkSuiteAddSheetDataDTO;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteTableProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.JsonUtils;
import pn.torn.goldeneye.utils.NumberUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Torn RW对冲上传数据公共逻辑层
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.05
 */
@Component
@RequiredArgsConstructor
public class TornRwUploadManager {
    private final LarkSuiteApi larkSuiteApi;
    private final TornSettingFactionManager factionManager;
    private final TornFactionRwDAO rwDao;
    private final LarkSuiteProperty larkSuiteProperty;

    /**
     * 上传对冲数据
     */
    public void uploadRwData(TornFactionRwDO rw, List<PlayerAttackStatDO> attackList) {
        LarkSuiteTableProperty table = larkSuiteProperty.findTable(TornConstants.TABLE_RW_FIERCE);
        String appToken = table.getAppToken();
        String tenantToken = getTenantToken();

        if (rw.getLarksuiteSheetId() == null) {
            addNewSheet(rw, tenantToken, appToken);
        }

        appendData(rw, attackList, tenantToken,
                larkSuiteProperty.findTable(TornConstants.TABLE_RW_FIERCE).getAppToken());
    }

    /**
     * 获取Tenant Token
     */
    private String getTenantToken() {
        AtomicReference<String> tenantToken = new AtomicReference<>();
        larkSuiteApi.sendSelfRequest(client -> {
            InternalTenantAccessTokenReq req = InternalTenantAccessTokenReq.newBuilder()
                    .internalTenantAccessTokenReqBody(InternalTenantAccessTokenReqBody.newBuilder()
                            .appId(larkSuiteProperty.getSelfAppId())
                            .appSecret(larkSuiteProperty.getSelfAppSecret())
                            .build())
                    .build();

            InternalTenantAccessTokenResp resp = client.auth().v3().tenantAccessToken().internal(req);
            String respStr = new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8);
            TenantTokenVO respBody = JsonUtils.jsonToObj(respStr, TenantTokenVO.class);
            tenantToken.set(respBody.getTenantAccessToken());
            return resp;
        });

        return tenantToken.get();
    }

    /**
     * 添加工作表
     */
    private void addNewSheet(TornFactionRwDO rw, String tenantToken, String appToken) {
        TornSettingFactionDO faction = factionManager.getIdMap().get(rw.getFactionId());
        String title = DateTimeUtils.convertToString(rw.getStartTime().toLocalDate()) +
                " " +
                faction.getFactionShortName();
        LarkSuiteSheetDataVO resp = larkSuiteApi.sendRequest(new LarkSuiteAddSheetDTO(appToken, title),
                tenantToken, LarkSuiteSheetVO.class);

        String sheetId = resp.getReplies().getFirst().getAddSheet().getProperties().getSheetId();
        rw.setLarksuiteSheetId(sheetId);
        rwDao.lambdaUpdate()
                .set(TornFactionRwDO::getLarksuiteSheetId, sheetId)
                .eq(TornFactionRwDO::getId, rw.getId())
                .update();
    }

    /**
     * 追加数据
     */
    private void appendData(TornFactionRwDO rw, List<PlayerAttackStatDO> attackList,
                            String tenantToken, String appToken) {
        String startRowColumn = "A1";
        String endRowColumn = "R" + (attackList.size() + 2);
        LarkSuiteAddSheetDataDTO param = new LarkSuiteAddSheetDataDTO(appToken, rw.getLarksuiteSheetId(),
                startRowColumn, endRowColumn);

        insertColumnHeader(rw, param);
        for (PlayerAttackStatDO attack : attackList) {
            param.addRow(List.of(
                    attack.getUserId(),
                    attack.getNickname(),
                    attack.getTotalAttacks(),
                    attack.getHospCount(),
                    attack.getLeaveCount(),
                    attack.getAssistCount(),
                    attack.getLostCount(),
                    attack.getTotalCombatDuration(),
                    attack.getAvgCombatDuration(),
                    attack.getOnlineOpponentCount(),
                    attack.getAvgOpponentElo(),
                    attack.getTotalRounds(),
                    NumberUtils.addDelimiters(attack.getDamageScore()),
                    NumberUtils.addDelimiters(attack.getDamageDealt()),
                    NumberUtils.addDelimiters(attack.getDamageTaken()),
                    attack.getSyringeUsed(),
                    attack.getSpecialAmmoRounds(),
                    attack.getDebuffTempCount()
            ));
        }

        larkSuiteApi.sendRequest(param, tenantToken, BaseResponse.class);
    }

    /**
     * 插入表头
     */
    private void insertColumnHeader(TornFactionRwDO rw, LarkSuiteAddSheetDataDTO param) {
        param.addRow(List.of(rw.getFactionName() + " VS " + rw.getOpponentFactionName() + " 对冲战斗数据统计",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        param.addRow(List.of("ID", "昵称", "攻击次数", "Hosp", "Leave", "Assist", "Lost",
                "战斗耗时(秒)", "平均耗时(秒)", "攻击在线数", "对手平均ELO",
                "总回合数", "输出评分", "输出伤害", "承受伤害", "打针数", "特殊子弹回合", "烟/闪/泪/椒"));
    }

    @Data
    private static class TenantTokenVO {
        /**
         * Tenant Token
         */
        @JsonProperty("tenant_access_token")
        private String tenantAccessToken;
    }
}