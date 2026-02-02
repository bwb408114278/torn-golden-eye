package pn.torn.goldeneye.configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgSocketBuilder;
import pn.torn.goldeneye.napcat.send.msg.PrivateMsgSocketBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.base.BasePrivateMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.DocStrategyImpl;
import pn.torn.goldeneye.napcat.strategy.manage.PrivateDocStrategyImpl;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.JsonUtils;
import pn.torn.goldeneye.utils.NumberUtils;

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
 * @version 0.5.0
 * @since 2025.07.09
 */
@Slf4j
@Component
@Order(InitOrderConstants.BOT)
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
    private List<BaseGroupMsgStrategy> groupMsgStrategyList;
    @Resource
    private List<BasePrivateMsgStrategy> privateMsgStrategyList;
    @Resource
    private DocStrategyImpl docStrategy;
    @Resource
    private PrivateDocStrategyImpl privateDocStrategy;
    @Resource
    private TornSettingFactionManager factionManager;
    @Resource
    private ProjectProperty projectProperty;

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
        boolean isPrivateMessage = rawMessage.contains("\"message_type\":\"private\"");
        if (!isGroupMessage && !isPrivateMessage) {
            return;
        }

        QqRecMsg msg = JsonUtils.jsonToObj(rawMessage, QqRecMsg.class);
        if (!isCommandMsg(msg)) {
            return;
        }

        String[] msgArray = msg.getMessage().getFirst().getData().getText().split("#", 3);
        if (msgArray.length < 2) {
            return;
        }

        if (isGroupMessage) {
            handleGroupMsg(msg, msgArray);
        } else {
            handlePrivateMsg(msg, msgArray);
        }
    }

    /**
     * 判断是否为指令消息
     *
     * @param msg 群聊消息
     * @return true为是
     */
    private boolean isCommandMsg(QqRecMsg msg) {
        return msg.getMessage().size() == 1 &&
                "text".equals(msg.getMessage().getFirst().getType()) &&
                msg.getMessage().getFirst().getData().getText().startsWith("g#");
    }

    /**
     * 处理群聊消息
     */
    private void handleGroupMsg(QqRecMsg msg, String[] msgArray) {
        TornSettingFactionDO faction = factionManager.getGroupIdMap().get(msg.getGroupId());

        if (!StringUtils.hasText(msgArray[1])) {
            GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
            List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, docStrategy);
            paramList.forEach(builder::addMsg);
            replyMsg(faction, builder.build());
            return;
        }

        for (BaseGroupMsgStrategy strategy : groupMsgStrategyList) {
            if (strategy.getCommand().equalsIgnoreCase(msgArray[1])) {
                // 不是特定群的功能，直接返回
                if (!strategy.getCustomGroupId().isEmpty() && !strategy.getCustomGroupId().contains(msg.getGroupId())) {
                    return;
                }

                GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
                if (invalidAdmin(msg.getUserId(), strategy, faction)) {
                    builder.addMsg(new TextQqMsg("没有对应的权限"));
                } else {
                    List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, strategy);
                    paramList.forEach(builder::addMsg);
                }
                // 从缓存中重新取一遍，防止修改禁言状态后取得值不对
                faction = factionManager.getGroupIdMap().get(msg.getGroupId());
                replyMsg(faction, builder.build());
                break;
            }
        }
    }

    /**
     * 处理私聊消息
     */
    private void handlePrivateMsg(QqRecMsg msg, String[] msgArray) {
        if (!StringUtils.hasText(msgArray[1])) {
            PrivateMsgSocketBuilder builder = new PrivateMsgSocketBuilder().setUserId(msg.getUserId());
            List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, privateDocStrategy);
            paramList.forEach(builder::addMsg);
            replyMsg(true, builder.build());
            return;
        }

        for (BasePrivateMsgStrategy strategy : privateMsgStrategyList) {
            if (strategy.getCommand().equalsIgnoreCase(msgArray[1])) {
                List<? extends QqMsgParam<?>> paramList = strategy.handle(msg.getSender(),
                        msgArray.length > 2 ? msgArray[2] : "");
                if (!CollectionUtils.isEmpty(paramList)) {
                    PrivateMsgSocketBuilder builder = new PrivateMsgSocketBuilder().setUserId(msg.getUserId());
                    paramList.forEach(builder::addMsg);
                    replyMsg(true, builder.build());
                }
                break;
            }
        }
    }

    /**
     * 构建机器人回复消息
     */
    private List<? extends QqMsgParam<?>> buildReplyMsg(QqRecMsg msg, String[] msgArray, BaseGroupMsgStrategy strategy) {
        try {
            return strategy.handle(msg.getGroupId(), msg.getSender(), msgArray.length > 2 ? msgArray[2] : "");
        } catch (BizException e) {
            return strategy.buildTextMsg(e.getMsg());
        }
    }

    /**
     * 校验管理员
     *
     * @return true为没有管理员权限
     */
    private boolean invalidAdmin(long userId, BaseGroupMsgStrategy strategy, TornSettingFactionDO faction) {
        if (projectProperty.getAdminId().contains(userId)) {
            return false;
        } else if (strategy.isNeedSa()) {
            return true;
        }

        if (strategy.getRoleType() == null) {
            return false;
        }
        // 如果是Leader, 默认有权限
        List<Long> leaderList = faction != null ? NumberUtils.splitToLongList(faction.getGroupAdminIds()) : List.of();
        if (leaderList.contains(userId)) {
            return false;
        }
        // OC指挥官
        if (TornFactionRoleTypeEnum.OC_COMMANDER.equals(strategy.getRoleType())) {
            List<Long> ocCommanderList = faction != null ?
                    NumberUtils.splitToLongList(faction.getOcCommanderIds()) : List.of();
            return !ocCommanderList.contains(userId);
        }
        // 战争指挥官
        if (TornFactionRoleTypeEnum.WAR_COMMANDER.equals(strategy.getRoleType())) {
            List<Long> warCommanderList = faction != null ?
                    NumberUtils.splitToLongList(faction.getWarCommanderIds()) : List.of();
            return !warCommanderList.contains(userId);
        }
        // 军需官
        if (TornFactionRoleTypeEnum.QUARTERMASTER.equals(strategy.getRoleType())) {
            List<Long> quartermaster = faction != null ?
                    NumberUtils.splitToLongList(faction.getQuartermasterIds()) : List.of();
            return !quartermaster.contains(userId);
        }

        return true;
    }

    /**
     * 回复消息
     */
    private void replyMsg(TornSettingFactionDO faction, BotSocketReqParam param) {
        replyMsg(faction != null && !faction.getMsgBlock(), param);
    }

    /**
     * 回复消息
     */
    private void replyMsg(boolean valid, BotSocketReqParam param) {
        if (valid) {
            Thread.ofVirtual().name("msg-processor", System.nanoTime()).start(() -> sendMessage(param));
        }
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