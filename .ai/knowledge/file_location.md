# 项目文件位置

## 元信息
- 文档类型：项目文件位置 知识库
- 适用项目：Golden-Eye
- 适用版本：1.2.0及以上
- 最后更新：2026.06.29
- 维护人：Bai
- 状态：有效

---

本文档用于帮助 AI 快速理解本项目的代码结构、关键文件位置和用途。  
当需要功能开发、排查问题、重构或生成代码时，可优先参考本文档。

## 目录结构说明

```text
├── build/                                                                      # 构建项目镜像需要的文件
│   └── docker-compose.yml                                                      # Docker compose启动文件
├── src/                                                                        # 代码根目录
│   ├── main/                                                                   # 功能代码
│   │   ├── java.pn.torn.goldeneye/                                             # java代码根目录
│   │   │   ├── configuration/                                                  # 项目配置
│   │   │   │   └── DynamicTaskService.java                                     # 动态定时任务
│   │   │   ├── constants/                                                      # 常量
│   │   │   │   ├── bot/                                                        # 机器人相关常量
│   │   │   │   │   └── BotCommands.java                                        # 机器人指令
│   │   │   │   └── torn/                                                       # Torn相关常量
│   │   │   │       ├── enums/                                                  # 枚举常量
│   │   │   │       │   ├── stocks/                                             # 股票相关枚举
│   │   │   │       │   │   └── StockPersonalityEnum.java                       # 股票个性化类型
│   │   │   │       │   └── user/                                               # 用户相关枚举
│   │   │   │       │       └── TornUserStatusEnum.java                         # 用户状态枚举
│   │   │   │       └── SettingConstants.java                                   # 系统配置项常量
│   │   │   ├── napcat/                                                         # napcat交互
│   │   │   │   └── strategy/                                                   # 接受Socket消息后的处理策略
│   │   │   │       ├── faction/                                                # 帮派相关功能
│   │   │   │       │   ├── attack/                                             # 帮派攻击记录相关功能
│   │   │   │       │   │   ├── publish/                                        # 可公开访问功能
│   │   │   │       │   │   │   └── FactionRwReviveRankStrategyImpl.java        # RW神医榜
│   │   │   │       │   │   └── BaseRwStrategy.java                             # RW基础策略
│   │   │   │       │   └── crime/                                              # Crime相关功能
│   │   │   │       │       ├── OcIdleRankStrategyImpl.java                     # OC空转榜
│   │   │   │       │       ├── OcLuckyRankStrategyImpl.java                    # OC欧皇榜
│   │   │   │       │       ├── OcRateQueryStrategyImpl.java                    # OC个人成功率
│   │   │   │       │       ├── OcRecommendStrategyImpl.java                    # OC推荐
│   │   │   │       │       ├── OcSuccessRankTableBuilder.java                  # OC成功率榜构建器
│   │   │   │       │       └── OcUnluckyRankStrategyImpl.java                  # OC非酋榜
│   │   │   │       └── vip/                                                    # VIP相关功能
│   │   │   │           └── VipNoticeSetStrategyImpl.java                       # 设置VIP提醒
│   │   │   ├── repository/                                                     # 持久层
│   │   │   │   ├── dao/                                                        # 数据库持久层访问
│   │   │   │   │   ├── faction/                                                # 帮派相关功能
│   │   │   │   │   │   └── oc/                                                 # OC相关功能
│   │   │   │   │   │       ├── TornFactionOcDAO.java                           # OC相关DAO
│   │   │   │   │   │       └── TornFactionOcSlotDAO.java                       # OC岗位相关DAO
│   │   │   │   │   └── torn/                                                   # Torn相关功能
│   │   │   │   │       └── TornAttackLogDAO.java                               # 攻击日志相关功能
│   │   │   │   ├── mapper/                                                     # Mapper相关
│   │   │   │   │   ├── faction/                                                # 帮派相关功能
│   │   │   │   │   │   └── oc/                                                 # OC相关功能
│   │   │   │   │   │       ├── TornFactionOcMapper.java                        # OC相关Mapper
│   │   │   │   │   │       └── TornFactionOcSlotMapper.java                    # OC岗位相关Mapper
│   │   │   │   │   └── torn/                                                   # Torn相关功能
│   │   │   │   │       └── TornAttackLogMapper.java                            # 攻击日志相关功能
│   │   │   │   └── model/                                                      # 数据对应模型
│   │   │   │       └── faction/                                                # 帮派相关功能
│   │   │   │           ├── attack/                                             # 帮派攻击相关功能
│   │   │   │           │   ├── AttackTimeWindowDO.java                         # 对冲时间窗口
│   │   │   │           │   └── TornFactionRwReviveDO.java                      # RW复活记录
│   │   │   │           └── oc/                                                 # OC相关功能
│   │   │   │               ├── OcSuccessRankDO.java                            # OC成功率排行
│   │   │   │               ├── TornFactionOcDO.java                            # 帮派OC表
│   │   │   │               ├── TornFactionOcIdleRankDO.java                    # OC空转榜查询结果
│   │   │   │               └── TornFactionOcSlotDO.java                        # 帮派OC岗位表
│   │   │   └── torn/                                                           # Torn相关
│   │   │       ├── manager/                                                    # 公共逻辑层
│   │   │       │   ├── faction/                                                # 帮派相关功能
│   │   │       │   │   ├── attack/                                             # 攻击记录相关
│   │   │       │   │   │   └── TornRwReviveManager.java                        # RW复活公共逻辑
│   │   │       │   │   └── crime/                                              # OC相关功能
│   │   │       │   │       ├── recommend/                                      # OC推荐相关功能
│   │   │       │   │       │   └── TornOcRecommendManager.java                 # OC推荐公共逻辑
│   │   │       │   │       └── TornFactionOcSlotManager.java                   # 帮派OC岗位公共逻辑
│   │   │       │   └── setting/                                                # 配置相关功能
│   │   │       │       └── SysSettingManager.java                              # 系统配置公共逻辑、缓存
│   │   │       ├── model/                                                      # Torn相关模型
│   │   │       │   └── faction/                                                # 帮派相关功能
│   │   │       │       ├── crime/                                              # Crime相关功能
│   │   │       │       │   ├── TornFactionCrimeRequireItemVO.java              # OC岗位需要物品响应参数
│   │   │       │       │   └── TornFactionCrimeSlotVO.java                     # 帮派OC岗位响应参数
│   │   │       │       └── revive/                                             # 复活相关功能
│   │   │       │           ├── TornFactionReviveVO.java                        # 帮派复活数据响应参数
│   │   │       │           └── TornFactionReviveDTO.java                       # 帮派复活请求参数
│   │   │       └── service/                                                    # 业务逻辑层
│   │   │           ├── data/                                                   # 数据相关功能
│   │   │           │   └── TornRwDataService.java                              # RW数据逻辑
│   │   │           ├── faction/                                                # 帮派相关功能
│   │   │           │   └── oc/                                                 # Crime相关功能
│   │   │           │       ├── recommend/                                      # OC推荐功能
│   │   │           │       │   └── TornOcRecommendService.java                 # OC推荐逻辑层
│   │   │           │       ├── TornFactionOcBenefitService.java                # 帮派OC收益逻辑层
│   │   │           │       └── TornOcCompleteNoticeService.java                # OC完成通知逻辑层
│   │   │           └── user/                                                   # 用户相关功能
│   │   │               └── StockTradeStrategyService.java                      # 股票交易策略逻辑层
│   │   └── resources/                                                          # 资源文件
│   │       ├── db.changelog/                                                   # Liquibase的数据库修改日志
│   │       │   └── 1.0.1-2.0.0/                                                # 1.0.1到2.0.0版本的改动
│   │       │       └── 1.2.0/                                                  # 1.2.0后的版本改动
│   │       │           ├── faction.yml                                         # 帮派相关改动
│   │       │           └── setting.yml                                         # 配置相关改动
│   │       └── mapper/                                                         # Mapper文件
│   │           ├── faction/                                                    # 帮派相关
│   │           │   └── oc/                                                     # OC相关
│   │           │       ├── TornFactionOcMapper.xml                             # 帮派OC表
│   │           │       └── TornFactionOcSlotMapper.xml                         # 帮派OC岗位表
│   │           └── torn/                                                       # Torn相关
│   │               └── TornAttackLogMapper.xml                                 # 攻击日志相关功能
│   └── test/                                                                   # 攻击日志表
│       └── java.pn.torn.goldeneye/                                             # 测试代码根目录
│           └── torn/                                                           # Torn相关
│               └── service/                                                    # 业务逻辑层
│                   └── faction/                                                # 帮派相关功能
│                       └── oc                                                  # Crime相关功能
│                           ├── recommend/                                      # OC推荐功能
│                           │   └── TornOcRecommendServiceTest.java             # OC推荐功能测试
│                           └── TornFactionOcBenefitServiceTest.java            # 帮派OC收益功能测试
├── pom.xml                                                                     # Maven构建项目依赖
└── README.md                                                                   # 项目说明文档
```