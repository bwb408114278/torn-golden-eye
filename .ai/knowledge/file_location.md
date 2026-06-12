# 项目文件位置

本文档用于帮助 AI 快速理解本项目的代码结构、关键文件位置和用途。  
当需要功能开发、排查问题、重构或生成代码时，可优先参考本文档。

## 目录结构说明

```text
├── build/                                                                      # 构建项目镜像需要的文件
├── src/                                                                        # 代码根目录
│  ├── main/                                                                   # 功能代码
│  │    └── java/                                                               # java代码
│  │        └── pn.torn.goldeneye/                                              # 项目根目录
│  │            └── napcat/                                                     # napcat交互
│  │                └── strategy/                                               # 接受Socket消息后的处理策略
│  │                    └── faction/                                            # 帮派相关功能
│  │                        └── crime/                                          # Crime相关功能
│  │                            └── OcRateQueryStrategyImpl.java                # 查询OC成功率策略实现
│  └── test/                                                                   # 测试代码
└── README.md                                                                   # 项目说明文档
```

# 元信息
- 文档类型：项目文件位置 知识库
- 适用项目：Golden-Eye
- 适用版本：1.2.0及以上
- 最后更新：2026.06.12
- 维护人：Bai
- 状态：有效