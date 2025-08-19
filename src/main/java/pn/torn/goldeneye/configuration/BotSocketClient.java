package pn.torn.goldeneye.configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.msg.receive.GroupRecMsg;
import pn.torn.goldeneye.msg.send.GroupMsgSocketBuilder;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.utils.JsonUtils;
import pn.torn.goldeneye.utils.StrMatchingUtils;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web Socket客户端机器人
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.09
 */
@Slf4j
@Component
@Order(1)
public class BotSocketClient {
    // 连接配置
    @Value("${bot.server.addr}")
    private String serverAddr;
    @Value("${bot.server.port.socket}")
    private String serverSocketPort;
    @Value("${bot.server.token}")
    private String serverToken;
    private static final Duration SERVER_HEARTBEAT_TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final Duration INITIAL_RECONNECT_DELAY = Duration.ofSeconds(2);
    private static final int MAX_CONCURRENT_MESSAGES = 15;
    // 状态控制
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final Semaphore rateLimiter = new Semaphore(MAX_CONCURRENT_MESSAGES);
    private final AtomicLong lastHeartbeatTime = new AtomicLong();
    // 线程管理
    private final ScheduledExecutorService connectionMonitor = Executors.newSingleThreadScheduledExecutor();
    private Session session;
    @Resource
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Resource
    private List<BaseMsgStrategy> msgStrategyList;

    @PostConstruct
    public void init() {
        connect();
        startConnectionMonitor();
    }

    /**
     * 连接服务器
     */
    @SuppressWarnings("resource")
    public synchronized void connect() {
        if (isConnected.get()) return;

        try {
            resetConnectionState();
            log.info("正在连接Nap cat服务器...");

            ClientManager client = ClientManager.createClient();
            String wsUrl = "ws://" + serverAddr + ":" + serverSocketPort + "/?access_token=" + serverToken;
            client.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig config) {
                    BotSocketClient.this.session = session;
                    isConnected.set(true);
                    reconnectAttempts.set(0);
                    log.info("✅ 已连接至Nap cat服务器");
                    lastHeartbeatTime.set(System.currentTimeMillis());

                    // 注册消息处理器
                    session.addMessageHandler(String.class, message -> handleIncomingMessage(message));
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                    handleDisconnect("❌ 连接关闭: " + closeReason.getReasonPhrase());
                }

                @Override
                public void onError(Session session, Throwable thr) {
                    handleDisconnect("连接错误: " + thr.getMessage());
                }
            }, ClientEndpointConfig.Builder.create().build(), new URI(wsUrl));
        } catch (Exception e) {
            log.error("连接失败: ", e);
            scheduleReconnect();
        }
    }

    /**
     * 发送消息
     */
    public void sendMessage(BotSocketReqParam param) {
        if (!isConnected.get() || session == null) {
            log.error("发送失败: 连接未就绪");
            return;
        }

        try {
            if (rateLimiter.tryAcquire(2000, TimeUnit.MILLISECONDS)) {
                try {
                    String msg = JsonUtils.objToJson(param);
                    session.getBasicRemote().sendText(msg);
                    log.debug("➡️ 发送消息: " + msg);
                } finally {
                    rateLimiter.release();
                }
            } else {
                log.error("发送超时: 流量控制阻塞超过2000ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("消息发送失败: ", e);
            scheduleReconnect();
        }
    }

    private void handleDisconnect(String message) {
        isConnected.set(false);
        log.info(message);
        scheduleReconnect();
    }

    private void resetConnectionState() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.error("重连Nap cat报错: ", e);
            }
        }
        session = null;
    }

    /**
     * 心跳检测
     */
    private void startConnectionMonitor() {
        connectionMonitor.scheduleAtFixedRate(() -> {
            if (!isConnected.get()) return;

            long currentTime = System.currentTimeMillis();
            long lastActive = lastHeartbeatTime.get();
            Duration timeSinceLastHeartbeat = Duration.ofMillis(currentTime - lastActive);

            if (timeSinceLastHeartbeat.compareTo(SERVER_HEARTBEAT_TIMEOUT) > 0) {
                log.error("心跳丢失: 超过" + timeSinceLastHeartbeat.getSeconds() + "秒未收到心跳信号");
                scheduleReconnect();
            }
        }, 10, 10, TimeUnit.SECONDS); // 每10秒检查一次
    }

    /**
     * 流量控制
     */
    private void handleIncomingMessage(String rawMessage) {
        // 更新心跳时间戳（任何消息都算作心跳）
        lastHeartbeatTime.set(System.currentTimeMillis());

        try {
            if (!rateLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                log.error("处理消息超时: 丢弃消息");
                return;
            }

            virtualThreadExecutor.execute(() -> {
                try {
                    // 特殊处理：响应服务端心跳
                    if (isServerHeartbeat(rawMessage)) {
                        handleServerHeartbeat();
                        return;
                    }

                    // 处理普通消息
                    log.debug("处理消息: {}", rawMessage);
                    processMessage(rawMessage);
                } catch (Exception e) {
                    log.error("消息处理失败: ", e);
                } finally {
                    rateLimiter.release();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 是否是服务端心跳消息
     *
     * @param message 消息，格式为{"post_type":"meta_event","meta_event_type":"heartbeat"}
     * @return true为是
     */
    private boolean isServerHeartbeat(String message) {
        return message != null && message.contains("\"meta_event_type\":\"heartbeat\"");
    }

    /**
     * 处理服务端心跳
     */
    private void handleServerHeartbeat() {
        lastHeartbeatTime.set(System.currentTimeMillis());
        log.debug("接收到服务器心跳");
    }

    /**
     * 处理收到的消息
     *
     * @param rawMessage 原始消息数据
     */
    private void processMessage(String rawMessage) {
        // 检查是否为群消息
        boolean isGroupMessage = rawMessage.contains("\"message_type\":\"group\"");
        if (!isGroupMessage) {
            return;
        }

        GroupRecMsg msg = JsonUtils.jsonToObj(rawMessage, GroupRecMsg.class);
        if (!isCommandMsg(msg)) {
            return;
        }

        String[] msgArray = msg.getMessage().get(0).getData().getText().split("#", 3);
        // todo 模糊匹配
        List<String> strings = StrMatchingUtils.fuzzyMatching("", StrMatchingUtils.botCommandsMap);
        if (msgArray.length < 2) {
            return;
        }

        for (BaseMsgStrategy strategy : msgStrategyList) {
            if (ArrayUtils.contains(strategy.getGroupId(), msg.getGroupId()) && strategy.getCommand().equals(msgArray[1])) {
                List<? extends GroupMsgParam<?>> paramList = strategy.handle(msg.getGroupId(),
                        msgArray.length > 2 ? msgArray[2] : "");
                if (!CollectionUtils.isEmpty(paramList)) {
                    GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
                    paramList.forEach(builder::addMsg);
                    BotSocketReqParam param = builder.build();
                    Thread.ofVirtual().name("msg-processor", System.nanoTime()).start(() -> sendMessage(param));
                }
                break;
            }
        }
    }

    /**
     * 判断是否为指令消息
     *
     * @param msg 群聊消息
     * @return true为是
     */
    private boolean isCommandMsg(GroupRecMsg msg) {
        return msg.getMessage().size() == 1 &&
                "text".equals(msg.getMessage().get(0).getType()) &&
                msg.getMessage().get(0).getData().getText().startsWith("g#");
    }

    /**
     * 断线重连
     */
    private void scheduleReconnect() {
        if (!shouldReconnect.get()) {
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("超过最大重连次数(" + MAX_RECONNECT_ATTEMPTS + ")，停止尝试");
            return;
        }

        // 指数退避策略
        long delay = (long) (Math.pow(2, Math.min(attempt, 6)) * INITIAL_RECONNECT_DELAY.toSeconds());
        log.info("将在 " + delay + " 秒后尝试重连 (#" + attempt + ")");

        // 使用虚拟线程执行重连，避免阻塞监控线程
        Thread.startVirtualThread(new DelayAndConnect(delay));
    }

    /**
     * 资源下线
     */
    @PreDestroy
    public void shutdown() {
        shouldReconnect.set(false);
        log.info("正在关闭连接...");

        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (IOException e) {
            log.error("关闭连接时出错: ", e);
        }

        connectionMonitor.shutdownNow();
        virtualThreadExecutor.shutdown();
    }

    /**
     * 延迟后连接
     */
    @AllArgsConstructor
    private class DelayAndConnect implements Runnable {
        /**
         * 延迟秒数
         */
        private long delay;

        @Override
        public void run() {
            try {
                Thread.sleep(delay * 1000);
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}