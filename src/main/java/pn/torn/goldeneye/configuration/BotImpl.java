package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

/**
 * 机器人类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.06.22
 */
@Slf4j
class BotImpl implements Bot {
    /**
     * Web请求
     */
    private final RestClient restClient;
    /**
     * 系统设置
     */
    private final TornSettingFactionManager factionManager;
    private final ProjectProperty projectProperty;

    public BotImpl(String serverAddr, String serverPort, String serverToken,
                   TornSettingFactionManager factionManager, ProjectProperty projectProperty) {
        this.restClient = RestClient.builder()
                .baseUrl("http://" + serverAddr + ":" + serverPort)
                .defaultHeader(HttpHeaders.AUTHORIZATION, serverToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.factionManager = factionManager;
        this.projectProperty = projectProperty;
    }

    @Override
    public <T> ResponseEntity<T> sendRequest(BotHttpReqParam param, Class<T> responseType) {
        try {
            if (param.body() instanceof GroupMsgReqParam msg && projectProperty.getVipGroupId() != msg.getGroupId()) {
                TornSettingFactionDO faction = factionManager.getGroupIdMap().get(msg.getGroupId());
                if (faction == null || faction.getMsgBlock()) {
                    return null;
                }
            }

            RestClient.RequestBodySpec request = this.restClient
                    .method(param.method())
                    .uri(param.uri());

            if (param.body() != null) {
                request.body(param.body());
            }

            return request.retrieve().toEntity(responseType);
        } catch (Exception e) {
            log.error("发送Http Bot消息出错, 消息内容" + param.toString(), e);
            return null;
        }
    }
}