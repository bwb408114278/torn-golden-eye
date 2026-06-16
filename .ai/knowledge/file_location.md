# 项目文件位置

本文档用于帮助 AI 快速理解本项目的代码结构、关键文件位置和用途。  
当需要功能开发、排查问题、重构或生成代码时，可优先参考本文档。

## 目录结构说明

```text
├── build/                                                                      # 构建项目镜像需要的文件
├── src/                                                                        # 代码根目录
│  ├── main/                                                                    # 功能代码
│  │    ├── java/                                                               # java代码
│  │    │   └── pn.torn.goldeneye/                                              # 项目根目录
│  │    │       ├── configuration/                                              # 项目配置
│  │    │       │   └── DynamicTaskService.java                                 # 动态定时任务
│  │    │       ├── constants/                                                  # 常量
│  │    │       │   └── bot/                                                    # 机器人相关常量
│  │    │       │       └── BotCommands.java/                                   # 机器人指令
│  │    │       ├── napcat/                                                     # napcat交互
│  │    │       │   └── strategy/                                               # 接受Socket消息后的处理策略
│  │    │       │       └── faction/                                            # 帮派相关功能
│  │    │       │           └── crime/                                          # Crime相关功能
│  │    │       │               ├── OcIdleRankStrategyImpl.java                 # 查询OC空转榜策略实现
│  │    │       │               ├── OcRateQueryStrategyImpl.java                # 查询OC成功率策略实现
│  │    │       │               └── OcRecommendStrategyImpl.java                # 推荐加入OC策略实现
│  │    │       ├── repository/                                                 # 持久层
│  │    │       │   ├── dao/                                                    # 数据库持久层访问
│  │    │       │   │   └── faction/                                            # 帮派相关功能
│  │    │       │   │       └── oc/                                             # OC相关功能
│  │    │       │   │           ├── TornFactionOcDAO.java                       # OC相关DAO
│  │    │       │   │           └── TornFactionOcSlotDAO.java                   # OC岗位相关DAO
│  │    │       │   ├── mapper/                                                 # Mapper相关
│  │    │       │   │   └── faction/                                            # 帮派相关功能
│  │    │       │   │       └── oc/                                             # OC相关功能
│  │    │       │   │           ├── TornFactionOcMapper.java                    # OC相关Mapper
│  │    │       │   │           └── TornFactionOcSlotMapper.java                # OC岗位相关Mapper
│  │    │       │   └── model/                                                  # 数据对应模型
│  │    │       │       └── faction/                                            # 帮派相关功能
│  │    │       │           └── oc/                                             # OC相关功能
│  │    │       │               ├── TornFactionOcDO.java                        # 帮派OC表
│  │    │       │               ├── TornFactionOcIdleRankDO.java                # OC空转榜查询结果
│  │    │       │               └── TornFactionOcSlotDO.java                    # 帮派OC岗位表
│  │    │       └── torn/                                                       # Torn相关
│  │    │           ├── manager/                                                # 公共逻辑层
│  │    │           │   └── faction/                                            # 帮派相关功能
│  │    │           │       └── crime/                                          # OC相关功能
│  │    │           │           └── TornFactionOcSlotManager.java               # 帮派OC岗位公共逻辑
│  │    │           ├── model/                                                  # Torn相关模型
│  │    │           │   └── faction/                                            # 帮派相关功能
│  │    │           │       └── crime/                                          # Crime相关功能
│  │    │           │           └── TornFactionCrimeSlotVO.java                 # 帮派OC岗位返回数据结构
│  │    │           └── service/                                                # 业务逻辑层
│  │    │               └── faction/                                            # 帮派相关功能
│  │    │                   └── oc                                              # Crime相关功能
│  │    │                       └── TornFactionOcBenefitService.java            # 帮派OC收益业务
│  │    └── resources/                                                          # 资源文件
│  │        └── mapper/                                                         # Mapper文件
│  │            └── faction/                                                    # 帮派相关
│  │                └── oc/                                                     # OC相关
│  │                    ├── TornFactionOcMapper.xml                             # 帮派OC表
│  │                    └── TornFactionOcSlotMapper.xml                         # 帮派OC岗位表
│  └── test/                                                                    # 测试代码
│       └── java/                                                               # java代码
│           └── pn.torn.goldeneye/                                              # 项目根目录
│               └── torn/                                                       # Torn相关
│                   └── service/                                                # 业务逻辑层
│                       └── faction/                                            # 帮派相关功能
│                           └── oc                                              # Crime相关功能
│                               └── TornFactionOcBenefitServiceTest.java        # 帮派OC收益业务测试
└── README.md                                                                   # 项目说明文档
```

# 元信息
- 文档类型：项目文件位置 知识库
- 适用项目：Golden-Eye
- 适用版本：1.2.0及以上
- 最后更新：2026.06.12
- 维护人：Bai
- 状态：有效