package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VIP股票策略调度器 - 每分钟驱动数据轮次构建与启动补偿
 * <p>
 * 在生产环境下每分钟第10秒检查已结束但尚未完成的15分钟轮次,按round_time升序逐个处理:
 * 先构建bar,再构建特征,状态流转 PENDING -> BUILDING_BAR -> BUILDING_FEATURE -> READY -> 事务 -> COMPLETED;
 * 若bar构建结果为空(无采样数据)则标记为WAITING_DATA。
 * {@link #processSingleRound} 处理单个轮次时,若轮次已是READY状态则跳过bar与特征构建,
 * 直接加载快照并执行事务(用于事务失败后的重试场景)。
 * <p>
 * JVM内通过 {@link AtomicBoolean#compareAndSet(boolean, boolean)} 防重入,finally释放;
 * 数据库 {@code round_time} 部分唯一索引提供最终幂等,不引入Redis锁或ShedLock等新依赖。
 *
 * <h3>启动补偿</h3>
 * <ol>
 *   <li>验证VIP组合槽位完整性</li>
 *   <li>初始化当月风格/成熟度/风险草稿记录</li>
 *   <li>存在轮次构建或研究义务时,与定时入口复用同一JVM防重入标记抢占处理权</li>
 *   <li>抢占成功后在同一try/finally内执行历史重建、未完成轮次处理与拒绝观察结算,
 *       finally释放标记,确保启动补偿真实补建bar</li>
 * </ol>
 * 每个初始化步骤独立try-catch,单步失败仅记录日志不阻塞后续;通知投递保持独立语义。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipStockAlertScheduler {
    /**
     * 买入规则版本(RANGE绝对趋势保护自1.1.0起生效)
     */
    public static final String BUY_RULE_VERSION = StockRuleVersion.BUY;
    /**
     * 卖出规则版本
     */
    public static final String SELL_RULE_VERSION = StockRuleVersion.SELL;
    /**
     * 仓位分配规则版本
     */
    public static final String ALLOCATION_RULE_VERSION = StockRuleVersion.ALLOCATION;
    /**
     * 消息通知规则版本
     */
    public static final String MESSAGE_RULE_VERSION = StockRuleVersion.MESSAGE;

    private final Stock15mBarBuildService barBuildService;
    private final Stock15mFeatureBuildService featureBuildService;
    private final TornStockMarketRoundDAO roundDao;
    private final StockHistoryRebuildService historyRebuildService;
    private final StockPortfolioInitService portfolioInitService;
    private final StockMonthlyStateInitService monthlyStateInitService;
    private final StockNoticeSendService noticeSendService;
    private final StockRejectedObservationService rejectedObservationService;
    private final StockMarketRoundLoader roundLoader;
    private final StockRoundTransactionService transactionService;
    private final StockMarketClock marketClock;
    private final ProjectProperty projectProperty;
    private final StockAlertRuntimeGate runtimeGate;
    private final StockMarketRoundFactory roundFactory;

    /**
     * JVM内防重入标记,同一时刻仅允许一个轮次处理流程
     */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 每分钟第10秒执行轮次调度
     * <p>
     * 执行前置检查:
     * <ol>
     *   <li>非生产环境直接返回</li>
     *   <li>读取 {@link StockAlertRuntimeGate} 运行时门禁,无轮次构建、无研究义务且无PENDING通知时返回</li>
     *   <li>{@link AtomicBoolean#compareAndSet(boolean, boolean)} 抢占防重入标记失败时返回</li>
     * </ol>
     * 通过后按固定顺序执行:
     * <ol>
     *   <li>需要构建轮次时先为最近已结束桶幂等建立PENDING轮次,
     *       再调用 {@link #processPendingRounds(boolean, LocalDateTime)} 处理已结束但未完成的轮次,
     *       先补建可能积压的理论入场bar,避免后续拒绝观察把尚可重建的理论入场误判为缺失</li>
     *   <li>存在未结算拒绝观察时结算到期研究义务(此时理论入场bar已尽可能补建)</li>
     *   <li>存在PENDING通知且正式消息开关允许时调用 {@code noticeSendService.sendPendingNotices()}</li>
     * </ol>
     * 总开关关闭但存在活跃批次时,仍继续构建存量管理所需轮次(退出/恢复/灾难关闭/冷却),
     * 仅禁止新买入;历史PENDING通知投递不受轮次总开关与数据构建结果影响。
     */
    @Scheduled(cron = "10 * * * * ?", zone = "Asia/Shanghai")
    public void executeRound() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();
        if (!decision.shouldBuildRounds() && !decision.shouldSendPendingNotices()) {
            log.debug("VIP股票策略调度-无轮次构建义务与待投递通知,跳过本次调度");
            return;
        }

        if (!processing.compareAndSet(false, true)) {
            log.warn("VIP股票策略调度-上一轮处理尚未完成,跳过本次调度");
            return;
        }

        try {
            // 固定顺序: 先为最近已结束桶幂等建立PENDING轮次, 再补建未完成轮次bar,
            // 再结算到期拒绝观察, 最后投递PENDING通知。
            // 若先结算拒绝观察, 紧邻理论入场bar因停机/前一轮失败/调度积压尚未写入时,
            // 会被提前结算为NO_THEORETICAL_ENTRY并永久写入resolvedAt, 后续补建bar不再重算。
            if (decision.shouldBuildRounds()) {
                LocalDateTime currentEndedBucket = marketClock.currentEndedBucket();
                ensurePendingRound(currentEndedBucket);
                processPendingRounds(decision.allowNewEntry(), currentEndedBucket);
            }
            if (decision.manageResearchObligations()) {
                rejectedObservationService.resolveAllDueObservations(marketClock.now());
            }
            if (decision.shouldSendPendingNotices()) {
                noticeSendService.sendPendingNotices();
            }
        } finally {
            processing.set(false);
        }
    }

    /**
     * 为最近已结束桶幂等建立PENDING轮次。
     * <p>
     * 仅当 {@link StockAlertRuntimeGate.RuntimeDecision#shouldBuildRounds()} 判定存在
     * 轮次构建义务(ALERT开启、存在活跃正式/影子批次或未结算拒绝观察)时调用。
     * 使用数据库部分唯一索引 + {@code ON CONFLICT DO NOTHING} 保证双入口/重启重试只落一行。
     * 创建失败时记录roundTime并抛出,阻断本轮后续对同一新桶的"假处理";
     * 下一轮可通过幂等插入或未完成查询恢复。
     *
     * @param currentEndedBucket 最近已结束桶时间
     */
    private void ensurePendingRound(LocalDateTime currentEndedBucket) {
        TornStockMarketRoundDO round = buildPendingRound(currentEndedBucket);
        try {
            int inserted = roundDao.insertPendingRoundIgnoreConflict(round);
            if (inserted > 0) {
                log.info("VIP股票策略调度-为最近已结束桶建立PENDING轮次, roundTime={}", currentEndedBucket);
            } else {
                log.debug("VIP股票策略调度-最近已结束桶PENDING轮次已存在,跳过重复创建, roundTime={}",
                        currentEndedBucket);
            }
        } catch (Exception e) {
            log.error("VIP股票策略调度-创建轮次失败, roundTime={}", currentEndedBucket, e);
            throw e;
        }
    }

    /**
     * 构建最近已结束桶的PENDING轮次记录(含固定规则版本,stockCount/attemptCount初始为0)。
     *
     * @param currentEndedBucket 最近已结束桶时间
     * @return 未保存的PENDING轮次记录
     */
    private TornStockMarketRoundDO buildPendingRound(LocalDateTime currentEndedBucket) {
        return roundFactory.createRound(currentEndedBucket, StockRoundStatusEnum.PENDING.getCode());
    }

    /**
     * 处理已结束但尚未完成的轮次
     * <p>
     * 使用调用方计算的当前已结束桶时间(与生产者 {@link #ensurePendingRound(LocalDateTime)}
     * 共用同一桶,避免重复计算),查询该时间之前全部未完成轮次并按
     * round_time升序逐个处理。每个轮次:
     * <ol>
     *   <li>状态置为BUILDING_BAR,调用 {@link Stock15mBarBuildService#buildBars(LocalDateTime)}</li>
     *   <li>bar构建为空时状态置为WAITING_DATA,跳过特征构建</li>
     *   <li>状态置为BUILDING_FEATURE,调用 {@link Stock15mFeatureBuildService#buildFeatures(LocalDateTime)}</li>
     *   <li>状态置为COMPLETED,记录completedAt</li>
     * </ol>
     * 每处理完一个轮次检查防重入标记是否仍持有,标记丢失时中断处理。
     * 单个轮次异常时记录错误并将状态置为FAILED_RETRYABLE,不中断后续轮次。
     *
     * @param allowNewEntry      是否允许创建新的正式/候选影子批次,透传给轮次事务
     * @param currentEndedBucket 最近已结束桶时间(含生产者已建立的PENDING轮次)
     */
    public void processPendingRounds(boolean allowNewEntry, LocalDateTime currentEndedBucket) {
        List<TornStockMarketRoundDO> pendingRounds = roundDao.selectPendingRoundsBefore(currentEndedBucket);
        if (CollectionUtils.isEmpty(pendingRounds)) {
            log.debug("VIP股票策略调度-无待处理轮次, currentEndedBucket={}", currentEndedBucket);
            return;
        }

        log.info("VIP股票策略调度-发现{}个待处理轮次, currentEndedBucket={}, allowNewEntry={}",
                pendingRounds.size(), currentEndedBucket, allowNewEntry);
        for (TornStockMarketRoundDO round : pendingRounds) {
            if (!processing.get()) {
                log.warn("VIP股票策略调度-防重入标记已释放,中断轮次处理");
                break;
            }

            LocalDateTime roundTime = round.getRoundTime();
            try {
                processSingleRound(round, roundTime, allowNewEntry);
            } catch (Exception e) {
                log.error("VIP股票策略调度-轮次处理异常, roundTime={}", roundTime, e);
                markFailed(round, e);
            }
        }
    }

    /**
     * 应用启动后执行补偿初始化
     * <p>
     * 非生产环境直接返回。读取 {@link StockAlertRuntimeGate} 运行时门禁:
     * 总开关关闭但存在活跃批次或未结算拒绝观察时,仍执行历史重建、未完成轮次处理与
     * 研究义务结算,但新买入被禁止;历史PENDING通知由正式消息开关独立决定投递。
     * 存在轮次构建或研究义务时,必须与定时入口复用同一JVM防重入标记:抢占成功后在
     * 同一try/finally内执行,finally释放标记,避免启动补偿因防重入标记未持有导致真实
     * 待处理轮次被跳过;抢占失败说明已有轮次流程在执行,不得并发处理,通知投递保持独立。
     * 依次执行(每步独立try-catch,单步失败不阻塞后续):
     * <ol>
     *   <li> {@link StockPortfolioInitService#verifyAndInitSlots()} 验证VIP组合槽位;验证未通过(修复或异常)
     *        时强制关闭本次启动的新买入({@code allowNewEntry=false}),存量退出管理与研究义务不受影响</li>
     *   <li> {@link StockHistoryRebuildService#rebuildFromLastCompleted(LocalDateTime)} 重建历史
     *        (先证据补齐,月度重算与自动确认必须在其后)</li>
     *   <li> {@link #processPendingRounds(boolean, LocalDateTime)} 处理未完成轮次(先补建理论入场bar,
     *        再结算拒绝观察,避免历史重建跳过的早期失败/积压轮次被误结算)</li>
     *   <li> {@link StockMonthlyStateInitService#recalculateCurrentMonthDrafts()} 重算当月未确认DRAFT
     *        (仅更新state_status=DRAFT且非人工覆盖记录,补齐证据后可再次计算)</li>
     *   <li> {@link StockMonthlyStateInitService#autoConfirmDraftStates(LocalDate)} 自动确认满足冻结条件的DRAFT
     *        (仅完整且非人工覆盖,confirmedBy=SYSTEM)</li>
     *   <li> {@link StockRejectedObservationService#resolveAllDueObservations(LocalDateTime)} 结算到期拒绝观察</li>
     * </ol>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();
        log.info("VIP股票策略调度-启动补偿开始, shouldBuildRounds={}, allowNewEntry={}, shouldSendPendingNotices={}",
                decision.shouldBuildRounds(), decision.allowNewEntry(), decision.shouldSendPendingNotices());

        boolean slotsValid = verifyPortfolioSlotsSafely();
        if (!slotsValid && decision.allowNewEntry()) {
            log.warn("VIP股票策略调度-组合槽位验证未通过(修复或失败),启动期强制关闭新买入");
            decision = forceNewEntryClosed(decision);
        }
        processStartupRoundWork(decision);
        sendStartupPendingNoticesSafely(decision);

        log.info("VIP股票策略调度-启动补偿完成");
    }

    /**
     * 验证VIP组合槽位完整性。
     * <p>
     * 槽位验证失败或服务抛出异常时返回false,由启动补偿据此强制关闭新买入(fail-closed);
     * 存量管理(未完成轮次处理)与研究义务不受影响。
     *
     * @return true表示槽位验证通过(完整且金额校验通过);false表示验证失败或未通过
     */
    private boolean verifyPortfolioSlotsSafely() {
        try {
            return portfolioInitService.verifyAndInitSlots();
        } catch (Exception e) {
            log.error("VIP股票策略调度-组合槽位验证失败,启动期强制关闭新买入", e);
            return false;
        }
    }

    /**
     * 生成关闭新买入的运行时判定副本。
     * <p>
     * 仅将 {@code allowNewEntry} 强制为false,其余分量原样透传,保证存量退出/研究义务/通知判定不变。
     *
     * @param decision 原始运行时判定
     * @return allowNewEntry=false的判定副本
     */
    private StockAlertRuntimeGate.RuntimeDecision forceNewEntryClosed(
            StockAlertRuntimeGate.RuntimeDecision decision) {
        return new StockAlertRuntimeGate.RuntimeDecision(
                decision.shouldBuildRounds(),
                decision.manageExistingBatches(),
                decision.manageResearchObligations(),
                false,
                decision.shouldSendPendingNotices(),
                decision.ruleMode(),
                decision.existsActiveBatches(),
                decision.existsPendingRejectedObservation());
    }

    /**
     * 为当月缺失股票的初始化DRAFT草稿,仅应在历史重建补齐证据之后调用。
     * 失败仅记录日志不阻塞后续步骤。
     */
    private void initCurrentMonthSafely() {
        try {
            monthlyStateInitService.initCurrentMonth();
        } catch (Exception e) {
            LocalDate effectiveMonth = marketClock.today().withDayOfMonth(1);
            log.error("VIP股票策略调度-月度状态初始化失败, effectiveMonth={}, 继续后续步骤", effectiveMonth, e);
        }
    }

    /**
     * 启动补偿的轮次工作区: 存在轮次构建或研究义务时,与定时入口复用同一JVM防重入标记,
     * 抢占成功后在统一try/finally内按固定顺序执行历史重建、未完成轮次处理、
     * 月度状态缺失初始化、未确认DRAFT重算、自动确认与拒绝观察结算,finally释放标记;
     * 抢占失败说明已有轮次流程在执行,跳过补偿处理。
     * <p>
     * 历史补建是月度重算/自动确认的证据前置: 首次历史桶创建或重建失败时必须阻断同次的
     * 月度状态初始化、重算与自动确认(fail-closed),防止"无证据继续下游";存量退出管理
     * (未完成轮次处理)仍可继续,但新入场强制关闭。
     *
     * @param decision 运行时判定结果
     */
    private void processStartupRoundWork(StockAlertRuntimeGate.RuntimeDecision decision) {
        boolean needsRoundWork = decision.shouldBuildRounds() || decision.manageResearchObligations();
        if (!needsRoundWork) {
            return;
        }

        if (!processing.compareAndSet(false, true)) {
            log.warn("VIP股票策略调度-启动补偿检测到已有轮次流程在执行,跳过轮次补偿处理");
            return;
        }

        try {
            LocalDateTime currentEndedBucket = marketClock.currentEndedBucket();
            boolean historyRebuildOk = true;
            if (decision.shouldBuildRounds()) {
                historyRebuildOk = rebuildStartupHistorySafely();
                boolean effectiveAllowNewEntry = decision.allowNewEntry() && historyRebuildOk;
                processStartupPendingRoundsSafely(effectiveAllowNewEntry, currentEndedBucket);
            }
            // 月度状态: 历史补建失败时阻断同次初始化/重算/自动确认(证据前置),
            // 避免"无证据继续下游"的冷启动假象;存量退出管理不受影响。
            if (historyRebuildOk) {
                // 历史重建补齐证据后,再为缺失股票初始化当月DRAFT(先证据、后DRAFT、后确认)
                if (decision.shouldBuildRounds()) {
                    initCurrentMonthSafely();
                }
                // 月度状态: 先重算当月未确认DRAFT(仅DRAFT且非人工覆盖),
                // 再自动确认满足冻结条件的记录。
                recalculateCurrentMonthDraftsSafely();
                autoConfirmCurrentMonthDraftsSafely();
            } else {
                log.error("VIP股票策略调度-历史补建失败,阻断同次月度状态初始化/重算/自动确认,"
                        + "新入场强制关闭, 存量退出管理继续");
            }
            if (decision.manageResearchObligations()) {
                resolveStartupObservationsSafely();
            }
        } finally {
            processing.set(false);
        }
    }

    /**
     * 从最后已完成轮次之后重建历史bar与特征;失败时阻断同次月度下游并返回false。
     *
     * @return true表示历史补建成功(或无需补建);false表示历史补建失败
     */
    private boolean rebuildStartupHistorySafely() {
        try {
            LocalDateTime currentEndedBucket = marketClock.currentEndedBucket();
            historyRebuildService.rebuildFromLastCompleted(currentEndedBucket);
            return true;
        } catch (Exception e) {
            log.error("VIP股票策略调度-历史重建失败,阻断同次月度初始化/重算/自动确认,"
                    + "新入场强制关闭", e);
            return false;
        }
    }

    /**
     * 处理启动补偿时的未完成轮次,失败仅记录日志不阻塞后续步骤。
     *
     * @param allowNewEntry      是否允许创建新的正式/候选影子批次
     * @param currentEndedBucket 最近已结束桶时间
     */
    private void processStartupPendingRoundsSafely(boolean allowNewEntry, LocalDateTime currentEndedBucket) {
        try {
            processPendingRounds(allowNewEntry, currentEndedBucket);
        } catch (Exception e) {
            log.error("VIP股票策略调度-启动补偿处理未完成轮次失败", e);
        }
    }

    /**
     * 重算当月未确认DRAFT月度状态,失败仅记录日志不阻塞后续步骤。
     * <p>
     * 历史补建失败时,重算结果仍为DRAFT/fail-closed(证据不足不满足自动确认条件),
     * 不允许把"没有补齐证据"误写为已确认。
     */
    private void recalculateCurrentMonthDraftsSafely() {
        try {
            int recalculated = monthlyStateInitService.recalculateCurrentMonthDrafts();
            log.info("VIP股票策略调度-启动补偿月度状态重算完成, recalculated={}", recalculated);
        } catch (Exception e) {
            log.error("VIP股票策略调度-启动补偿月度状态重算失败,继续后续步骤", e);
        }
    }

    /**
     * 自动确认当月满足冻结条件的DRAFT月度状态,失败仅记录日志不阻塞后续步骤。
     */
    private void autoConfirmCurrentMonthDraftsSafely() {
        try {
            LocalDate effectiveMonth = marketClock.today().withDayOfMonth(1);
            int confirmed = monthlyStateInitService.autoConfirmDraftStates(effectiveMonth);
            log.info("VIP股票策略调度-启动补偿月度状态自动确认完成, effectiveMonth={}, confirmed={}",
                    effectiveMonth, confirmed);
        } catch (Exception e) {
            log.error("VIP股票策略调度-启动补偿月度状态自动确认失败,继续后续步骤", e);
        }
    }

    /**
     * 结算到期拒绝观察,失败仅记录日志不阻塞后续步骤。
     */
    private void resolveStartupObservationsSafely() {
        try {
            rejectedObservationService.resolveAllDueObservations(marketClock.now());
        } catch (Exception e) {
            log.error("VIP股票策略调度-拒绝观察启动补偿失败,继续后续步骤", e);
        }
    }

    /**
     * 投递启动补偿期间的历史PENDING通知,失败仅记录日志。
     *
     * @param decision 运行时判定结果
     */
    private void sendStartupPendingNoticesSafely(StockAlertRuntimeGate.RuntimeDecision decision) {
        if (!decision.shouldSendPendingNotices()) {
            return;
        }
        try {
            noticeSendService.sendPendingNotices();
        } catch (Exception e) {
            log.error("VIP股票策略调度-启动补偿投递PENDING通知失败", e);
        }
    }

    /**
     * 处理单个轮次: 构建bar -> 构建特征 -> 标记READY -> 加载快照 -> 执行事务
     * <p>
     * bar构建结果为空时(无采样数据)将轮次标记为WAITING_DATA并返回,不继续构建特征。
     * 特征构建完成后标记READY,然后事务外加载RoundSnapshot并调用TransactionService执行组合事务。
     * 只有TransactionService成功后才标记COMPLETED。
     * 规则版本字段(buy/sell/allocation/message)在首次进入BUILDING_BAR时填充。
     *
     * @param round         待处理轮次记录
     * @param roundTime     轮次锚定的bar时间
     * @param allowNewEntry 是否允许创建新的正式/候选影子批次,透传给轮次事务
     */
    private void processSingleRound(TornStockMarketRoundDO round, LocalDateTime roundTime, boolean allowNewEntry) {
        log.info("VIP股票策略调度-开始处理轮次, roundTime={}, 当前状态={}", roundTime, round.getRoundStatus());

        boolean needsDataBuild = !StockRoundStatusEnum.READY.getCode().equals(round.getRoundStatus());
        if (needsDataBuild) {
            if (!buildRoundData(round, roundTime)) {
                return;
            }
        } else {
            log.info("VIP股票策略调度-轮次已是READY,跳过数据构建, roundTime={}", roundTime);
        }

        StockMarketRoundLoader.RoundSnapshot snapshot = roundLoader.loadRoundSnapshot(roundTime);
        LocalDateTime actualProcessingTime = marketClock.now();
        transactionService.executeRound(roundTime, snapshot, allowNewEntry, actualProcessingTime);

        log.info("VIP股票策略调度-轮次事务完成, roundTime={}, actualProcessingTime={}", roundTime, actualProcessingTime);
    }

    /**
     * 构建轮次数据: bar -> 特征 -> READY
     *
     * @param round     待处理轮次记录
     * @param roundTime 轮次锚定的bar时间
     * @return true表示数据构建成功可继续事务;false表示无数据(WAITING_DATA)应跳过
     */
    private boolean buildRoundData(TornStockMarketRoundDO round, LocalDateTime roundTime) {
        LocalDateTime now = marketClock.now();
        round.setRoundStatus(StockRoundStatusEnum.BUILDING_BAR.getCode());
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setBuyRuleVersion(BUY_RULE_VERSION);
        round.setSellRuleVersion(SELL_RULE_VERSION);
        round.setAllocationRuleVersion(ALLOCATION_RULE_VERSION);
        round.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        round.setStartedAt(now);
        roundDao.updateById(round);

        List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(roundTime);
        if (CollectionUtils.isEmpty(bars)) {
            log.warn("VIP股票策略调度-轮次bar构建为空, 标记WAITING_DATA, roundTime={}", roundTime);
            round.setRoundStatus(StockRoundStatusEnum.WAITING_DATA.getCode());
            round.setUsableStockCount(0);
            round.setCompletedAt(now);
            roundDao.updateById(round);
            return false;
        }

        round.setExpectedStockCount(bars.size());
        round.setRoundStatus(StockRoundStatusEnum.BUILDING_FEATURE.getCode());
        roundDao.updateById(round);

        int featureCount = featureBuildService.buildFeatures(roundTime).size();
        round.setUsableStockCount(featureCount);

        round.setRoundStatus(StockRoundStatusEnum.READY.getCode());
        roundDao.updateById(round);
        log.info("VIP股票策略调度-轮次数据就绪, roundTime={}, 预期股票={}, 特征股票={}",
                roundTime, bars.size(), featureCount);
        return true;
    }

    /**
     * 将轮次标记为可重试失败并记录错误信息
     *
     * @param round 待标记的轮次记录
     * @param e     触发失败的异常
     */
    private void markFailed(TornStockMarketRoundDO round, Exception e) {
        try {
            round.setRoundStatus(StockRoundStatusEnum.FAILED_RETRYABLE.getCode());
            round.setErrorMessage(e.getMessage());
            round.setCompletedAt(marketClock.now());
            roundDao.updateById(round);
        } catch (Exception updateEx) {
            log.error("VIP股票策略调度-标记轮次失败状态异常, roundTime={}", round.getRoundTime(), updateEx);
        }
    }
}
