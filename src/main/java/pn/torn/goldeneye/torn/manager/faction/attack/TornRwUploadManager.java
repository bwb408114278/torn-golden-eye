package pn.torn.goldeneye.torn.manager.faction.attack;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenReq;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenReqBody;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenResp;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.larksuite.sheet.LarkSuiteAddSheetDTO;
import pn.torn.goldeneye.base.larksuite.sheet.LarkSuiteSheetReplyVO;
import pn.torn.goldeneye.base.larksuite.sheet.LarkSuiteSheetVO;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteTableProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.JsonUtils;

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
    private final LarkSuiteProperty larkSuiteProperty;

    /**
     * 上传拍卖行数据
     */
    public void uploadAuction(List<PlayerAttackStatDO> attackList) {
        LarkSuiteTableProperty table = larkSuiteProperty.findTable(TornConstants.TABLE_RW_FIERCE);
        String appToken = table.getAppToken();
        String tenantToken = getTenantToken();
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
     *
     * @return 工作表ID
     */
    private String addNewSheet(TornFactionRwDO rw, String tenantToken, String appToken) {
        TornSettingFactionDO faction = factionManager.getIdMap().get(rw.getFactionId());
        String title = DateTimeUtils.convertToString(rw.getStartTime().toLocalDate()) +
                " " +
                faction.getFactionShortName();
        List<LarkSuiteSheetReplyVO> resp = larkSuiteApi.sendRequest(new LarkSuiteAddSheetDTO(appToken, title),
                tenantToken, LarkSuiteSheetVO.class);
        return resp.getFirst().getAddSheet().getProperties().getSheetId();
    }

    private void insertData(List<PlayerAttackStatDO> attackList, String tenantToken, String appToken, String sheetId) {

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