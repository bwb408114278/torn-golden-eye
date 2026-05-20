package pn.torn.goldeneye.configuration.socket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.configuration.socket.dispatch.BotMessageDispatcher;
import pn.torn.goldeneye.configuration.socket.event.BotSendMessageEvent;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.utils.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 机器人Socket客户端
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.05.20
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(InitOrderConstants.BOT)
public class BotSocketClient {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final BotMessageDispatcher messageDispatcher;
    @Value("${bot.server.addr}")
    private String serverAddr;
    @Value("${bot.server.port.socket}")
    private String serverSocketPort;
    @Value("${bot.server.token}")
    private String serverToken;

    private static final Duration SERVER_HEARTBEAT_TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final Duration INITIAL_RECONNECT_DELAY = Duration.ofSeconds(2);

    /**
     * 发送并发控制
     */
    private static final int MAX_CONCURRENT_SEND_MESSAGES = 15;
    /**
     * 接收处理并发控制
     */
    private static final int MAX_CONCURRENT_RECEIVE_MESSAGES = 15;

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong lastHeartbeatTime = new AtomicLong();

    private final Semaphore sendRateLimiter = new Semaphore(MAX_CONCURRENT_SEND_MESSAGES);
    private final Semaphore receiveRateLimiter = new Semaphore(MAX_CONCURRENT_RECEIVE_MESSAGES);

    private final ScheduledExecutorService connectionMonitor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    private final AtomicReference<Session> sessionRef = new AtomicReference<>();

    @PostConstruct
    public void init() {
        connect();
        startConnectionMonitor();
    }

    @EventListener
    public void handleSendMessageEvent(BotSendMessageEvent event) {
        sendMessage(event.getParam());
    }

    /**
     * 连接服务器
     */
    @SuppressWarnings("resource")
    public synchronized void connect() {
        if (isConnected.get()) {
            return;
        }

        try {
            resetConnectionState();
            log.info("正在连接Nap cat服务器...");

            ClientManager client = ClientManager.createClient();
            String wsUrl = "ws://" + serverAddr + ":" + serverSocketPort + "/?access_token=" + serverToken;

            client.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig config) {
                    sessionRef.set(session);
                    isConnected.set(true);
                    reconnectAttempts.set(0);
                    reconnectScheduled.set(false);
                    lastHeartbeatTime.set(System.currentTimeMillis());

                    log.info("✅ 已连接至Nap cat服务器");

                    session.addMessageHandler(String.class, BotSocketClient.this::handleIncomingMessage);
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
            log.error("连接失败", e);
            scheduleReconnect();
        }
    }

    /**
     * 发送消息
     */
    public void sendMessage(BotSocketReqParam param) {
        Session currentSession = sessionRef.get();
        if (!isConnected.get() || currentSession == null) {
            log.error("发送失败: 连接未就绪");
            return;
        }

        try {
            if (!sendRateLimiter.tryAcquire(2, TimeUnit.SECONDS)) {
                log.error("发送超时: 流量控制阻塞超过2000ms");
                return;
            }

            try {
                String msg = JsonUtils.objToJson(param);
                currentSession.getBasicRemote().sendText(msg);
                log.debug("➡️ 发送消息: {}", msg);
            } finally {
                sendRateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("消息发送失败", e);
            scheduleReconnect();
        }
    }

    /**
     * 处理收到的原始消息
     */
    private void handleIncomingMessage(String rawMessage) {
        lastHeartbeatTime.set(System.currentTimeMillis());

        try {
            if (!receiveRateLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                log.error("处理消息超时: 丢弃消息");
                return;
            }

            virtualThreadExecutor.execute(() -> {
                try {
                    if (isServerHeartbeat(rawMessage)) {
                        handleServerHeartbeat();
                        return;
                    }

                    log.debug("处理消息: {}", rawMessage);
                    messageDispatcher.dispatch(rawMessage);
                } catch (Exception e) {
                    log.error("消息处理失败", e);
                } finally {
                    receiveRateLimiter.release();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 是否为服务端心跳
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
     * 处理断连
     */
    private void handleDisconnect(String message) {
        isConnected.set(false);
        log.info(message);
        scheduleReconnect();
    }

    /**
     * 重置连接状态
     */
    private void resetConnectionState() {
        Session currentSession = sessionRef.getAndSet(null);
        if (currentSession != null && currentSession.isOpen()) {
            try {
                currentSession.close();
            } catch (IOException e) {
                log.error("重置连接状态失败", e);
            }
        }
    }

    /**
     * 心跳检测
     */
    private void startConnectionMonitor() {
        connectionMonitor.scheduleAtFixedRate(() -> {
            if (!isConnected.get()) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            long lastActive = lastHeartbeatTime.get();
            Duration timeSinceLastHeartbeat = Duration.ofMillis(currentTime - lastActive);

            if (timeSinceLastHeartbeat.compareTo(SERVER_HEARTBEAT_TIMEOUT) > 0) {
                log.error("心跳丢失: 超过{}秒未收到心跳信号", timeSinceLastHeartbeat.getSeconds());
                scheduleReconnect();
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 断线重连
     */
    private void scheduleReconnect() {
        if (!shouldReconnect.get()) {
            return;
        }

        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectScheduled.set(false);
            log.error("超过最大重连次数({})，停止尝试", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = (long) (Math.pow(2, Math.min(attempt, 6)) * INITIAL_RECONNECT_DELAY.toSeconds());
        log.info("将在 {} 秒后尝试重连 (#{} )", delay, attempt);

        reconnectScheduler.schedule(() -> {
            try {
                reconnectScheduled.set(false);
                connect();
            } catch (Exception e) {
                reconnectScheduled.set(false);
                log.error("重连任务执行失败", e);
            }
        }, delay, TimeUnit.SECONDS);
    }

    /**
     * 资源关闭
     */
    @PreDestroy
    public void shutdown() {
        shouldReconnect.set(false);
        log.info("正在关闭连接...");

        try {
            Session currentSession = sessionRef.getAndSet(null);
            if (currentSession != null && currentSession.isOpen()) {
                currentSession.close();
            }
        } catch (IOException e) {
            log.error("关闭连接时出错", e);
        }

        connectionMonitor.shutdownNow();
        virtualThreadExecutor.shutdown();
    }
}