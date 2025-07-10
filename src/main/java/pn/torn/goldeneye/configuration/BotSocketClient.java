package pn.torn.goldeneye.configuration;

import jakarta.websocket.*;

import java.net.URI;

/**
 * Web Socket客户端机器人
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.09
 */
@ClientEndpoint
public class BotSocketClient {
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        System.out.println("✅ 已连接 Napcat WebSocket 服务器");
    }

    @OnMessage
    public void onMessage(String message) {
        // 1. 解析消息（通常是JSON格式）
        // 2. 判断是否为QQ群消息且符合特定格式
        // 3. 业务处理 & 构造回复内容
        System.out.println("收到消息: " + message);
    }

    @OnError
    public void onError(Throwable error) {
        error.printStackTrace();
    }

    public void sendMessage(String jsonMsg) throws Exception {
        session.getBasicRemote().sendText(jsonMsg);
    }

    public static void connect(String uri) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(BotSocketClient.class, URI.create(uri));
    }
}