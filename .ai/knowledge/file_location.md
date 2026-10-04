# 项目文件位置

## 元信息
- 文档类型：项目文件位置 知识库
- 适用项目：Golden-Eye
- 适用版本：1.2.0及以上
- 最后更新：2026.10.04
- 维护人：Bai
- 状态：有效

---

本文档用于帮助 AI 快速理解本项目的代码结构、关键文件位置和用途。  
当需要功能开发、排查问题、重构或生成代码时，可优先参考本文档。

> 注意：下方目录树为**选择性摘要**，仅列出核心与常用文件，不保证完整覆盖全部源码；
> 实际以 `src/main` 与 `src/test` 目录内容为准。

## 目录结构说明

```text
├── .ai/                                                                                # AI协作知识库与任务规范
│   └── knowledge/                                                                      # AI知识库
│       ├── oc/                                                                         # Torn OC相关知识库
│       │   ├── oc_delay_attribution_technical_design.md                                # OC延误归因（原因/时长/采样与验收基线）技术方案
│       │   └── oc_reassign_income_mode_timeline_technical_design.md                    # 大锅饭收益模式按月时间线（切换口径/展示/上线）技术方案
│       ├── stocks/                                                                     # Torn股票股票相关知识库
│       │   ├── data/                                                                   # 长期机器可读研究摘要
│       │   │   ├── stock_personality_2026_07.csv                                       # 35支股票风格指标快照
│       │   │   └── virtual_portfolio_validation_summary.json                           # 最终5槽策略、动态SELL、风格风险与证据等级摘要
│       │   ├── references/                                                             # 股票业务来源资料
│       │   │   └── Stock Market - Torncity WIKI.pdf                                    # Torn股票机制Wiki PDF
│       │   ├── stock_personality_full_history_2026_07.md                               # 2026-08候选预览（截至2026-07-23，M2）
│       │   ├── stock_personality_monthly_calibration.md                                # 股票滚动一年风格分类、门禁、迟滞与回放规范
│       │   ├── vip_stock_alert_strategy_background.md                                  # BUY/SELL淘汰结论和动态研究方向
│       │   ├── vip_stock_alert_technical_design.md                                     # VIP群股票虚拟组合、消息提醒及数据库技术方案
│       │   ├── vip_stock_private_analysis_upgrade_technical_design.md                  # 私聊Stock分析15m化+月度风格接入技术方案(1.8.0)
│       │   ├── vip_stock_virtual_portfolio_strategy.md                                 # 系统虚拟组合完整业务设计与开发主依据
│       │   └── virtual_portfolio_research_evidence.md                                  # 交易参考、冻结策略、组合与风格门禁研究证据
│       ├── activity_heatmap_design.md                                                  # 活跃度热力图完整设计、数据口径与固定RGB色板
│       ├── oc_reassign_range_technical_design.md                                       # PN/NOV大锅饭OC范围、生效边界、校准补算与收益查询技术方案
│       └── automated_build_deployment_pipeline_design.md                               # 自动流水线构建部署设计方案
├── build/                                                                              # 构建项目镜像需要的文件
│   └── docker-compose.yml                                                              # Docker compose启动文件
├── src/                                                                                # 代码根目录
│   ├── main/                                                                           # 功能代码
│   │   ├── java/pn/torn/goldeneye/                                                     # java代码根目录
│   │   │   ├── configuration/                                                          # 项目配置
│   │   │   │   └── DynamicTaskService.java                                             # 动态定时任务
│   │   │   ├── constants/                                                              # 常量
│   │   │   │   ├── bot/                                                                # 机器人相关常量
│   │   │   │   │   └── BotCommands.java                                                # 机器人指令
│   │   │   │   └── torn/                                                               # Torn相关常量
│   │   │   │       ├── enums/                                                          # 枚举常量
│   │   │   │       │   ├── stocks/                                                     # 股票相关枚举
│   │   │   │       │   │   └── StockPersonalityEnum.java                               # 股票个性化类型
│   │   │   │       │   └── user/                                                       # 用户相关枚举
│   │   │   │       │       └── TornUserStatusEnum.java                                 # 用户状态枚举
│   │   │   │       └── SettingConstants.java                                           # 系统配置项常量
│   │   │   ├── napcat/                                                                 # napcat交互
│   │   │   │   └── strategy/                                                           # 接受Socket消息后的处理策略
│   │   │   │       ├── faction/                                                        # 帮派相关功能
│   │   │   │       │   ├── attack/                                                     # 帮派攻击记录相关功能
│   │   │   │       │   │   ├── publish/                                                # 可公开访问功能
│   │   │   │       │   │   │   └── FactionRwReviveRankStrategyImpl.java                # RW神医榜
│   │   │   │       │   │   └── BaseRwStrategy.java                                     # RW基础策略
│   │   │   │       │   └── crime/                                                      # Crime相关功能
│   │   │   │       │       ├── OcIdleRankStrategyImpl.java                             # OC空转榜
│   │   │   │       │       ├── OcLuckyRankStrategyImpl.java                            # OC欧皇榜
│   │   │   │       │       ├── OcRateQueryStrategyImpl.java                            # OC个人成功率
│   │   │   │       │       ├── OcRecommendStrategyImpl.java                            # OC推荐
│   │   │   │       │       ├── OcSuccessRankTableBuilder.java                          # OC成功率榜构建器
│   │   │   │       │       └── OcUnluckyRankStrategyImpl.java                          # OC非酋榜
│   │   │   │       └── vip/                                                            # VIP相关功能
│   │   │   │           └── VipNoticeSetStrategyImpl.java                               # 设置VIP提醒
│   │   │   ├── repository/                                                             # 持久层
│   │   │   │   ├── dao/                                                                # 数据库持久层访问
│   │   │   │   │   ├── faction/                                                        # 帮派相关DAO
│   │   │   │   │   │   └── oc/                                                         # OC相关DAO
│   │   │   │   │   │       ├── TornFactionOcDAO.java                                   # OC DAO
│   │   │   │   │   │       └── TornFactionOcSlotDAO.java                               # OC岗位相关DAO
│   │   │   │   │   └── torn/                                                           # Torn相关DAO
│   │   │   │   │       ├── stocks/                                                     # 股票相关DAO
│   │   │   │   │       │   ├── portfolio/                                              # VIP股票组合相关DAO
│   │   │   │   │       │   │   ├── TornStockVirtualBatchDAO.java                       # 虚拟交易批次DAO(含活跃正式/影子批次行锁查询)
│   │   │   │   │       │   │   ├── TornStockBatchMarkDAO.java                          # 批次逐轮标记DAO
│   │   │   │   │       │   │   ├── TornStockPortfolioSlotDAO.java                      # 组合槽位DAO
│   │   │   │   │       │   │   ├── TornStockMarketRoundDAO.java                        # 轮次记录DAO
│   │   │   │   │       │   │   ├── TornStockMarketBar15mDAO.java                       # 15分钟bar DAO
│   │   │   │   │       │   │   ├── TornStockStrategyFeature15mDAO.java                 # 15分钟策略特征DAO
│   │   │   │   │       │   │   ├── TornStockSignalEventDAO.java                        # 信号事件DAO
│   │   │   │   │       │   │   ├── TornStockSignalStateDAO.java                        # 信号边沿状态DAO
│   │   │   │   │       │   │   ├── TornStockMonthlyStateDAO.java                       # 月度风格状态DAO
│   │   │   │   │       │   │   ├── TornStockNoticeAuditDAO.java                        # 通知审计DAO
│   │   │   │   │       │   │   └── TornStockPortfolioAnnualSettlementDAO.java          # α正式仓年度结算台账DAO
│   │   │   │   │       │   └── TornStocksHistoryDAO.java                               # 股票历史DAO
│   │   │   │   │       └── TornAttackLogDAO.java                                       # 攻击日志DAO
│   │   │   │   ├── mapper/                                                             # Mapper相关
│   │   │   │   │   ├── faction/                                                        # 帮派相关Mapper
│   │   │   │   │   │   └── oc/                                                         # OC相关Mapper
│   │   │   │   │   │       ├── TornFactionOcMapper.java                                # OC Mapper
│   │   │   │   │   │       └── TornFactionOcSlotMapper.java                            # OC岗位相关Mapper
│   │   │   │   │   └── torn/                                                           # Torn相关Mapper
│   │   │   │   │       ├── stocks/                                                     # 股票相关Mapper(Java接口)
│   │   │   │   │       │   ├── portfolio/                                              # VIP股票组合相关Mapper
│   │   │   │   │       │   │   ├── TornStockVirtualBatchMapper.java                    # 虚拟批次表Mapper(含UPSERT与行锁查询)
│   │   │   │   │       │   │   ├── TornStockBatchMarkMapper.java                       # 批次逐轮标记Mapper
│   │   │   │   │       │   │   ├── TornStockPortfolioSlotMapper.java                   # 组合槽位Mapper
│   │   │   │   │       │   │   ├── TornStockMarketRoundMapper.java                     # 轮次记录Mapper
│   │   │   │   │       │   │   ├── TornStockMarketBar15mMapper.java                    # 15分钟bar Mapper
│   │   │   │   │       │   │   ├── TornStockStrategyFeature15mMapper.java              # 15分钟策略特征Mapper
│   │   │   │   │       │   │   ├── TornStockSignalEventMapper.java                     # 信号事件Mapper
│   │   │   │   │       │   │   ├── TornStockSignalStateMapper.java                     # 信号边沿状态Mapper
│   │   │   │   │       │   │   ├── TornStockMonthlyStateMapper.java                    # 月度风格状态Mapper
│   │   │   │   │       │   │   ├── TornStockNoticeAuditMapper.java                     # 通知审计Mapper
│   │   │   │   │       │   │   └── TornStockPortfolioAnnualSettlementMapper.java       # 年度结算台账Mapper
│   │   │   │   │       │   └── TornStocksHistoryMapper.java                            # 股票历史表Mapper
│   │   │   │   │       └── TornAttackLogMapper.java                                    # 攻击日志Mapper
│   │   │   │   └── model/                                                              # 数据对应模型
│   │   │   │       ├── faction/                                                        # 帮派相关功能
│   │   │   │       │   ├── attack/                                                     # 帮派攻击相关功能
│   │   │   │       │   │   ├── AttackTimeWindowDO.java                                 # 对冲时间窗口
│   │   │   │       │   │   └── TornFactionRwReviveDO.java                              # RW复活记录
│   │   │   │       │   └── oc/                                                         # OC相关功能
│   │   │   │       │       ├── OcSuccessRankDO.java                                    # OC成功率排行
│   │   │   │       │       ├── TornFactionOcDO.java                                    # 帮派OC表
│   │   │   │       │       ├── TornFactionOcIdleRankDO.java                            # OC空转榜查询结果
│   │   │   │       │       └── TornFactionOcSlotDO.java                                # 帮派OC岗位表
│   │   │   │       └── torn/                                                           # Torn相关模型
│   │   │   │           └── stocks/                                                     # 股票相关模型
│   │   │   │               └── portfolio/                                              # VIP股票组合相关DO
│   │   │   │                   ├── TornStockVirtualBatchDO.java                        # 虚拟交易批次DO
│   │   │   │                   ├── TornStockBatchMarkDO.java                           # 批次逐轮标记DO
│   │   │   │                   ├── TornStockPortfolioSlotDO.java                       # 组合槽位DO
│   │   │   │                   ├── TornStockMarketRoundDO.java                         # 轮次记录DO
│   │   │   │                   ├── TornStockMarketBar15mDO.java                        # 15分钟bar DO
│   │   │   │                   ├── TornStockStrategyFeature15mDO.java                  # 15分钟策略特征DO
│   │   │   │                   ├── TornStockSignalEventDO.java                         # 信号事件DO
│   │   │   │                   ├── TornStockSignalStateDO.java                         # 信号边沿状态DO
│   │   │   │                   ├── TornStockMonthlyStateDO.java                        # 月度风格状态DO
│   │   │   │                   ├── TornStockNoticeAuditDO.java                         # 通知审计DO
│   │   │   │                   └── TornStockPortfolioAnnualSettlementDO.java           # α正式仓年度结算台账DO
│   │   │   └── torn/                                                                   # Torn相关
│   │   │       ├── manager/                                                            # 公共逻辑层
│   │   │       │   ├── faction/                                                        # 帮派相关功能
│   │   │       │   │   ├── attack/                                                     # 攻击记录相关
│   │   │       │   │   │   └── TornRwReviveManager.java                                # RW复活公共逻辑
│   │   │       │   │   └── crime/                                                      # OC相关功能
│   │   │       │   │       ├── recommend/                                              # OC推荐相关功能
│   │   │       │   │       │   └── TornOcRecommendManager.java                         # OC推荐公共逻辑
│   │   │       │   │       └── TornFactionOcSlotManager.java                           # 帮派OC岗位公共逻辑
│   │   │       │   ├── torn/                                                           # Torn相关功能
│   │   │       │   │   └── stocks                                                      # 股票相关功能
│   │   │       │   │       └── StockRollingFeatureEngine.java                          # 股票滚动窗口设置特征值引擎
│   │   │       │   └── setting/                                                        # 配置相关功能
│   │   │       │       └── SysSettingManager.java                                      # 系统配置公共逻辑、缓存
│   │   │       ├── model/                                                              # Torn相关模型
│   │   │       │   ├── activity/                                                       # 活跃度热力图模型
│   │   │       │   │   ├── ActivityCaliberEnum.java                                    # 热力图统计口径(单日/单周/半月/典型周)
│   │   │       │   │   ├── ActivityQueryRange.java                                     # 查询日期范围(含口径→网格入口)
│   │   │       │   │   └── grid/                                                       # 口径网格布局子包(槽→行列映射,sealed)
│   │   │       │   │       ├── ActivityGridLayout.java                                 # 布局接口、Position与唯一布局工厂
│   │   │       │   │       ├── DayStripGridLayout.java                                 # 单周/半月N行日期×24小时列
│   │   │       │   │       ├── SingleDayGridLayout.java                                # 单日4行刻钟位×24小时列
│   │   │       │   │       └── WeekdayHourGridLayout.java                              # 典型周与对比图7行星期×24小时列
│   │   │       │   ├── faction/                                                        # 帮派相关模型
│   │   │       │   │   ├── crime/                                                      # Crime相关模型
│   │   │       │   │   │   ├── TornFactionCrimeRequireItemVO.java                      # OC岗位需要物品响应参数
│   │   │       │   │   │   └── TornFactionCrimeSlotVO.java                             # 帮派OC岗位响应参数
│   │   │       │   │   ├── oc/                                                         # OC相关模型
│   │   │       │   │   │   └── delay/                                                  # OC延误归因模型
│   │   │       │   │   │       ├── OcDelayCauseEntry.java                              # 单成员延误归因条目
│   │   │       │   │   │       └── OcDelayReasonEnum.java                              # OC延误原因枚举
│   │   │       │   │   └── revive/                                                     # 复活相关模型
│   │   │       │   │       ├── TornFactionReviveVO.java                                # 帮派复活数据响应参数
│   │   │       │   │       └── TornFactionReviveDTO.java                               # 帮派复活请求参数
│   │   │       │   └── torn/                                                           # Torn模型
│   │   │       │       └── stocks/                                                     # 股票相关模型
│   │   │       │           └── trade/                                                  # 股票交易相关模型
│   │   │       │               └── StockRollingState.java                              # 股票滚动窗口状态参数
│   │   │       └── service/                                                            # 业务逻辑层
│   │   │           ├── data/                                                           # 数据相关功能
│   │   │           │   └── TornRwDataService.java                                      # RW数据逻辑
│   │   │           ├── faction/                                                        # 帮派相关功能
│   │   │           │   └── oc/                                                         # Crime相关功能
│   │   │           │       ├── delay/                                                  # OC延误归因（原因判定与编解码）
│   │   │           │       │   ├── OcDelayCauseRecorder.java                           # 延误归因编解码与段结算
│   │   │           │       │   ├── OcDelayCauseService.java                            # 延误归因采样、结算与原因行渲染
│   │   │           │       │   └── OcDelayReasonResolver.java                          # 单成员延误原因判定
│   │   │           │       ├── recommend/                                              # OC推荐功能
│   │   │           │       │   └── TornOcRecommendService.java                         # OC推荐逻辑层
│   │   │           │       ├── planning/                                               # OC新队规划（8个子包）
│   │   │           │       │   ├── api/                                                # 门面、指令规划器、渲染（唯一生产入口包）
│   │   │           │       │   ├── snapshot/                                           # 快照加载、求解请求构造、占用统计
│   │   │           │       │   ├── chain/                                              # 高阶链模板与既有时间线重建
│   │   │           │       │   ├── timeline/                                           # 事件推进、状态剪枝、流动性验证、停转政策
│   │   │           │       │   ├── matching/                                           # 成员—岗位最小费用流匹配与准备时间
│   │   │           │       │   ├── search/                                             # 刷新向量搜索与组合评估
│   │   │           │       │   ├── evidence/                                           # 收益证据计算与经济价值比较
│   │   │           │       │   └── policy/                                             # 模式选点策略
│   │   │           │       ├── TornFactionOcBenefitService.java                        # 帮派OC收益逻辑层
│   │   │           │       └── TornOcCompleteNoticeService.java                        # OC完成通知与延误归因逻辑层
│   │   │           ├── stocks/                                                         # VIP股票虚拟组合与消息提醒
│   │   │           │   └── alert/                                                      # 股票提醒核心服务
│   │   │           │       ├── alpha/                                                  # α相位轨道决策、执行与通知
│   │   │           │       │   └── notice/                                             # α通知文案与审计写入
│   │   │           │       │       ├── StockAlphaHoldNoticeService.java                # α决策日继续持有通知审计写入
│   │   │           │       │       └── StockAlphaNoticeRenderer.java                   # α买卖/换仓/继续持有正文渲染
│   │   │           │       ├── monthly/                                                # 月度风格状态(1.8.0复活精简版,仅SYSTEM自动确认)
│   │   │           │       │   ├── StockMonthlyEvidenceComputer.java                   # 月度证据指标纯计算(raw/adjusted双口径)
│   │   │           │       │   ├── StockMonthlyEvidenceMetrics.java                    # 月度证据指标record
│   │   │           │       │   ├── StockMonthlyOutageWaiver.java                       # 已备案停机窗口豁免(日历指标adjusted口径)
│   │   │           │       │   ├── StockMonthlyPrevious.java                           # 上一确认月迟滞参考record
│   │   │           │       │   ├── StockMonthlyStateCalculator.java                    # 成熟度/六类风格/风险投票与迟滞纯计算
│   │   │           │       │   ├── StockMonthlyStateDraft.java                         # 月度状态计算结果record
│   │   │           │       │   └── StockMonthlyStateInitService.java                   # 月度DRAFT初始化/重算/SYSTEM自动确认与启动编排(分片)
│   │   │           │       ├── StockMarketRoundLoader.java                             # 轮次快照批量加载(事务外)
│   │   │           │       ├── StockRoundTransactionService.java                       # 轮次12步事务编排
│   │   │           │       ├── StockBatchPathService.java                              # 持仓路径更新与退出评估
│   │   │           │       ├── StockBatchExitService.java                              # 退出规则引擎
│   │   │           │       ├── StockEntrySettlementService.java                        # 待买/待卖批次结算
│   │   │           │       ├── StockRoundExitGuard.java                                # 同轮平仓股票候选过滤
│   │   │           │       ├── StockPortfolioService.java                              # 5槽组合槽位资金管理
│   │   │           │       ├── StockVirtualBatchAssembler.java                         # 批次字段组装器
│   │   │           │       ├── Stock15mBarBuildService.java                            # 15分钟bar构建
│   │   │           │       ├── Stock15mFeatureBuildService.java                        # 15分钟策略特征构建
│   │   │           │       ├── Stock15mTradeFeatureProvider.java                       # 私聊Stock分析15m特征取数与就绪分流(只读)
│   │   │           │       ├── StockDailySummaryService.java                           # 每日权益摘要
│   │   │           │       ├── StockMinuteRsiCalculator.java                           # 指令触发时分钟RSI(60)现算(1.8.0自user迁入,消除包依赖环)
│   │   │           │       ├── notice/                                                 # 通知组装与发送
│   │   │           │       │   ├── StockNoticeNoGenerator.java                         # 通知编号时间戳唯一实现
│   │   │           │       │   └── StockNoticeSendService.java                         # NapCat消息投递
│   │   │           │       └── settlement/                                             # α正式仓年度结算与年报
│   │   │           │           ├── StockAnnualSettlementCalculator.java                # 年度结算纯计算(区间按年钳制/年化)
│   │   │           │           ├── StockAnnualSettlementNoticeService.java             # 年度报告通知审计与投递
│   │   │           │           ├── StockAnnualSettlementRenderer.java                  # 年度报告正文纯渲染
│   │   │           │           ├── StockAnnualSettlementService.java                   # 年度结算编排(可证窗口/门禁/落库/通知)
│   │   │           │           └── VipStockAnnualSettlementScheduler.java              # 年度结算调度(00:05主入口与补偿)
│   │   │           └── user/                                                           # 用户相关功能
│   │   │               ├── StockMonthlyStyleResolver.java                              # 私聊Stock分析月度风格选月/沿用/停推解析(只读)
│   │   │               └── StockTradeStrategyService.java                              # 私聊Stock分析策略逻辑层(15m特征+月度风格)
│   │   └── resources/                                                                  # 资源文件
│   │       ├── db/changelog/                                                           # Liquibase的数据库修改日志
│   │       │   └── 1.0.1-2.0.0/                                                        # 1.0.1到2.0.0版本的改动
│   │       │       ├── 1.2.0/                                                          # 1.2.0后的版本改动
│   │       │       │   ├── faction.yaml                                                # 帮派相关改动
│   │       │       │   ├── setting.yaml                                                # 配置相关改动
│   │       │       │   └── stocks-portfolio.yaml                                       # VIP股票组合建表与索引改动
│   │       │       ├── 1.6.6/                                                          # 1.6.6版本改动
│   │       │       │   ├── stocks-alpha-hold-notice.yaml                               # α继续持有通知轨道列与幂等索引
│   │       │       │   └── stocks-annual-settlement.yaml                               # 年度结算台账与年报通知幂等索引
│   │       │       ├── 1.6.7/                                                          # 1.6.7版本改动
│   │       │       │   └── oc-delay-cause.yaml                                         # OC延误归因delay_cause加列
│   │       │       └── 1.8.0/                                                          # 1.8.0版本改动
│   │       │           └── stocks-monthly-state-revive.yaml                            # 月度风格状态表复活(preConditions幂等)
│   │       └── mapper/                                                                 # Mapper文件
│   │           ├── faction/                                                            # 帮派相关
│   │           │   └── oc/                                                             # OC相关
│   │           │       ├── TornFactionOcMapper.xml                                     # 帮派OC表Mapper
│   │           │       └── TornFactionOcSlotMapper.xml                                 # 帮派OC岗位表Mapper
│   │           └── torn/                                                               # Torn相关
│   │               ├── stocks/                                                         # 股票相关Mapper
│   │               │   ├── portfolio/                                                  # VIP股票组合相关Mapper
│   │               │   │   ├── TornStockVirtualBatchMapper.xml                         # 虚拟批次表Mapper(含UPSERT与行锁查询)
│   │               │   │   ├── TornStockBatchMarkMapper.xml                            # 批次逐轮标记Mapper
│   │               │   │   ├── TornStockPortfolioSlotMapper.xml                        # 组合槽位Mapper
│   │               │   │   ├── TornStockMarketRoundMapper.xml                          # 轮次记录Mapper
│   │               │   │   ├── TornStockMarketBar15mMapper.xml                         # 15分钟bar Mapper
│   │               │   │   ├── TornStockStrategyFeature15mMapper.xml                   # 15分钟策略特征Mapper
│   │               │   │   ├── TornStockSignalEventMapper.xml                          # 信号事件Mapper
│   │               │   │   ├── TornStockSignalStateMapper.xml                          # 信号边沿状态Mapper
│   │               │   │   ├── TornStockMonthlyStateMapper.xml                         # 月度风格状态Mapper
│   │               │   │   ├── TornStockNoticeAuditMapper.xml                          # 通知审计Mapper
│   │               │   │   └── TornStockPortfolioAnnualSettlementMapper.xml            # 年度结算台账Mapper
│   │               │   └── TornStocksHistoryMapper.xml                                 # 股票历史表Mapper
│   │               └── TornAttackLogMapper.xml                                         # 攻击日志表Mapper
│   └── test/                                                                           # 测试功能
│       └── java/pn/torn/goldeneye/                                                     # 测试代码根目录
│           └── torn/                                                                   # Torn相关
│               └── service/                                                            # 业务逻辑层
│                   ├── faction/                                                        # 帮派相关功能
│                   │   └── oc                                                          # Crime相关功能
│                   │       ├── delay/                                                  # OC延误归因测试
│                   │       │   ├── OcDelayCauseRecorderTest.java                       # 延误归因编解码与段结算测试
│                   │       │   └── OcDelayReasonResolverTest.java                      # 单成员延误原因判定测试
│                   │       ├── recommend/                                              # OC推荐功能
│                   │       │   └── TornOcRecommendServiceTest.java                     # OC推荐功能测试
│                   │       ├── TornFactionOcBenefitServiceTest.java                    # 帮派OC收益功能测试
│                   │       └── TornOcCompleteNoticeServiceTest.java                    # OC完成通知与延误归因测试
│                   ├── stocks/                                                         # 股票相关功能
│                   │   └── alert/                                                      # VIP股票提醒测试
│                   │       ├── alpha/                                                  # α相位轨道测试
│                   │       │   └── notice/                                             # α通知测试
│                   │       │       └── StockAlphaHoldNoticeServiceItTest.java          # α继续持有通知真实数据库集成测试
│                   │       ├── market/                                                 # 15m市场数据与私聊取数测试
│                   │       │   ├── StockMinuteRsiCalculatorTest.java                   # 分钟RSI现算测试(1.8.0自user迁入)
│                   │       │   └── TornStockStrategyFeature15mMapperTest.java          # 15m特征Mapper真实PostgreSQL集成测试
│                   │       ├── StockRoundTransactionServiceTest.java                   # 轮次12步事务编排测试
│                   │       ├── StockBatchPathServiceTest.java                          # 持仓路径更新与退出评估测试
│                   │       ├── StockBatchExitServiceTest.java                          # 退出规则引擎测试
│                   │       ├── StockEntrySettlementServiceTest.java                    # 待买/待卖批次结算测试
│                   │       ├── StockRoundExitGuardTest.java                            # 同轮平仓候选过滤测试
│                   │       └── settlement/                                             # α年度结算测试
│                   │           ├── StockAnnualSettlementCalculatorTest.java            # 年度结算纯计算测试
│                   │           ├── StockAnnualSettlementRendererTest.java              # 年度报告正文渲染测试
│                   │           ├── StockAnnualSettlementServiceTest.java               # 年度结算编排(幂等/窗口/门禁)测试
│                   │           └── TornStockPortfolioAnnualSettlementMapperTest.java   # 年度结算台账真实数据库测试
│                   └── user/                                                           # 用户相关功能测试
│                       └── Stock15mTradeFeatureProviderTest.java                       # 私聊Stock分析15m特征取数组件测试
├── pom.xml                                                                             # Maven构建项目依赖
└── README.md                                                                           # 项目说明文档
```
