# 自动构建部署流水线与任务排空设计方案

> 文档状态：详细设计待审核；流水线、Java、Docker Compose 和生产服务器均未实施  
> 适用项目版本：1.2.12 及以上  
> 设计日期：2026-07-22  
> 维护人：Bai  
> 重要约束：本文是方案知识库，不代表代码修改或生产部署授权。用户审核后仍需明确回复“开始实施”，才能修改项目文件；生产服务器初始化、首次发布和后续自动部署分别审批。

---

## 1. 目标

建设一条适用于 Golden-Eye 单实例生产环境的自动构建部署流水线：

1. GitHub Actions 自动编译、测试并构建 Docker 镜像；
2. 镜像推送到私有 GitHub Container Registry（GHCR）；
3. 生产群晖通过现有 NAS 用户和临时开放的 SSH 端口部署私有镜像；
4. 部署前让应用进入任务排空状态：固定 `@Scheduled` 任务不再开始，已开始的固定任务和 Bot 指令等待完成；
5. 动态任务不参与排空，漏执行或中断后继续使用项目现有启动补偿机制；
6. 容器重建命令固定在非 5 倍数分钟的第 15 秒发出；如果第 15 秒前未排空完成，则跳过当前分钟，等待下一个符合条件的第 15 秒；
7. 仅重建 `golden-eye`，不得重启 PostgreSQL、Redis、NapCat、Loki、Alloy 或 Grafana；
8. 新容器健康检查失败时自动恢复上一镜像；
9. 同时发布 `latest` 便捷标签和不可变 digest；生产部署、版本校验与回滚以 digest 为准；
10. 不向 GitHub Actions、日志或代码提交生产密码和 Token。

本方案不引入多实例，不引入 Redis/数据库分布式锁，也不实施蓝绿双容器。当前项目按单实例部署，两个应用实例并行会重复执行大量定时任务，不符合现状。

---

## 2. 当前项目事实

### 2.1 构建和部署现状

当前相关文件：

- `pom.xml`：JDK 21、Spring Boot 3.5.16，当前未引入 Actuator；
- `build/Dockerfile`：容器内执行 `mvn clean package -DskipTests`；
- `build/docker-compose.yml`：`golden-eye` 使用本地版本标签；
- `src/main/resources/application.yml`：当前未配置健康探针和关闭等待时间；
- 仓库中尚无 `.github/workflows/`；
- 生产环境为 amd64 群晖 Synology NAS，Docker 命令必须使用 `/usr/local/bin/docker`；只能使用现有 NAS 用户，执行 Docker 必须输入 sudo 密码；
- 现有镜像目录、Compose 目录和挂载目录固定，不能新建或迁移目录，但允许在现有 Compose 目录增加部署脚本、状态文件和锁文件；
- NAS SSH 端口不是长期开放状态，合并代码只负责构建镜像，生产部署必须在用户临时打开 SSH 端口后手工触发。

当前 Compose 的业务端口使用未绑定地址的端口映射，默认可能暴露到宿主机所有网卡；是否继续公网/局域网暴露应单独审计。新增的管理健康端口不得沿用这种方式，必须仅在容器内部访问或显式绑定 loopback。

当前 Dockerfile 的 `-DskipTests` 不构成 CI 测试门禁。正式流水线必须在镜像构建前独立执行完整 Maven 验证。

### 2.2 已识别的固定定时任务

| 类 | Cron | 典型触发点 |
|---|---|---|
| `TornStocksManager` | `5 * * * * ?` | 每分钟第 5 秒 |
| `VipSubscribeManager` | `0 */5 * * * ?` | 每 5 分钟第 0 秒 |
| `VipNoticeManager` | `10 */5 * * * ?` | 每 5 分钟第 10 秒 |
| `TornActivityCollectService` | `0 */15 * * * *` | 每 15 分钟第 0 秒 |
| `TornUserStateService` | `0 30 8 * * *` | 每天 08:30:00 |

项目中还有大量 `DynamicTaskService.updateTask()` 注册的一次性任务，并且这些任务已具备启动补偿机制。自动部署不修改、不暂停、不统计动态任务；动态任务若因重启漏执行或中断，由新实例启动后的现有补偿逻辑恢复。

### 2.3 当前异步执行现状

- 通用异步执行器：`ThreadPoolTaskExecutor virtualThreadExecutor`；
- 活跃度专用执行器：`SimpleAsyncTaskExecutor activityCollectExecutor`；
- 动态任务自行创建 `ThreadPoolTaskScheduler`，但不纳入本功能改造；
- Bot WebSocket 收到消息后提交到 `virtualThreadExecutor`；
- 多个业务类通过 `CompletableFuture.runAsync/supplyAsync` 提交子任务；
- 部分任务提交后会 `join()`，部分为 fire-and-forget；
- `BotSocketClient.shutdown()` 当前会直接关闭共享 `virtualThreadExecutor`，执行器生命周期职责需要统一。

任务排空范围限定为固定 `@Scheduled` 顶层任务和 Bot 指令。固定任务内部已经提交且会等待的异步子任务随顶层任务完成；共享执行器关闭等待作为 fire-and-forget 任务的停机兜底。动态任务明确排除在 activeTasks 统计之外。

---

## 3. 总体架构

```text
开发者 push / merge / 手工发布
             │
             ▼
GitHub Actions CI
  ├─ checkout
  ├─ JDK 21 Maven verify
  ├─ Docker Buildx 构建
  ├─ 推送私有 GHCR
  ├─ 解析并记录 sha256 digest
  └─ 生成构建证明/摘要
             │
             ▼
GitHub Production Environment
  ├─ 分支/Tag保护
  ├─ 用户临时打开 NAS SSH 端口
  ├─ 手工触发 production deployment
  └─ concurrency 串行化
             │
             ▼ 现有 NAS 用户 SSH
现有 Compose 目录部署脚本
  ├─ 校验 digest 与 run-id
  ├─ 获取部署锁
  └─ 通过 sudo -S 执行 Docker 命令
             │
             ▼
部署事务
  ├─ 保存当前 digest
  ├─ 拉取新 digest
  ├─ 请求应用进入 DRAINING
  ├─ 等待 activeTasks=0
  ├─ 等待非5倍数分钟的第15秒
  ├─ 仅重建 golden-eye
  ├─ 等待 readiness=UP
  ├─ 成功提交新 digest
  └─ 失败恢复上一 digest
```

### 3.1 核心原则

1. **合并只构建，部署手工触发**：合并时不要求 SSH 端口开放；用户打开 SSH 后再启动生产部署；
2. **先拉取和排空，后等待第15秒**：耗时操作不占停机时间；
3. **`latest` 用于便捷访问，digest 用于部署**：生产状态和回滚目标以 `sha256:...` 为准；
4. **先排空，后 SIGTERM**：尽量让固定任务和已开始指令自然完成；
5. **只重建应用容器**：禁止执行 Compose 全栈停止；
6. **部署事务 fail-closed**：无法证明安全时不切换；
7. **数据库迁移与镜像回滚分开看待**：镜像可回滚不等于数据库 Schema 可回滚。

---

## 4. 流水线触发与环境分层

### 4.1 CI 触发

建议对以下事件执行 CI：

- Pull Request：编译、测试、构建但不推送生产镜像；
- 受保护生产分支 push：编译、测试、构建并推送镜像，不连接 NAS；
- `workflow_dispatch`：用户打开 SSH 端口后，手工选择已成功构建的提交/digest 部署；
- 版本 Tag：可生成语义版本展示标签，但底层仍部署 digest。

生产分支名称在实施前根据仓库实际默认分支确认，不在方案中假设为 `main`。

### 4.2 GitHub Environment

建立 `production` Environment：

- 限制允许部署的分支/Tag；
- 保存生产 SSH 主机、SSH 端口、现有 NAS 用户、SSH 私钥、sudo 密码和主机指纹等 Secret；
- 手工触发本身作为“SSH 已打开且允许部署”的明确动作；仍可额外配置 Required Reviewer；
- Deployment Job 使用：

```yaml
environment: production
```

### 4.3 并发控制

同一时间只能有一个生产部署：

```yaml
concurrency:
  group: golden-eye-production
  cancel-in-progress: false
```

`cancel-in-progress` 必须为 `false`。新提交不能在旧部署事务中途取消旧任务，否则可能留下未完成的排空或回滚状态。

### 4.4 临时 SSH 端口流程

```text
代码合并
→ CI验证并推送私有镜像
→ 不连接NAS，构建阶段结束
→ 用户临时打开SSH端口
→ 用户手工触发deploy-production并选择目标构建
→ Workflow先执行SSH连通性和Host Key校验
→ 部署、健康检查或回滚完成
→ Workflow提示用户关闭SSH端口
```

GitHub Actions 无法自行打开 NAS/路由器 SSH 端口。SSH 连接失败时必须在拉取镜像或排空应用之前结束，不重试到超出用户预期开窗时间。

---

## 5. 私有 GHCR 镜像设计

### 5.1 镜像命名

展示标签：

```text
ghcr.io/<OWNER>/golden-eye:latest
ghcr.io/<OWNER>/golden-eye:sha-<COMMIT_SHA>
ghcr.io/<OWNER>/golden-eye:v<PROJECT_VERSION>
```

生产实际引用：

```text
ghcr.io/<OWNER>/golden-eye@sha256:<DIGEST>
```

`latest` 继续发布，便于人工查看和临时拉取，但不作为生产部署或回滚依据。原因是 `latest` 会被下一次构建覆盖，无法唯一证明某次 Workflow 的目标镜像，也无法稳定表达上一成功版本。

生产部署必须使用本次构建输出的 digest；展示用的 `latest`、`sha-*` 和版本 Tag 都指向同一个构建结果。

### 5.2 权限

GitHub Actions 推送镜像：

```yaml
permissions:
  contents: read
  packages: write
  attestations: write
  id-token: write
```

群晖拉取镜像使用专用只读凭据，只授予：

```text
read:packages
```

生产机凭据不得拥有源码写入、包写入或删除权限。

### 5.3 镜像可见性

GHCR Package 必须确认是 Private，并检查包的仓库继承权限。私有仓库和私有 Package 是两个需要分别验证的权限对象。

### 5.4 镜像内容约束

新增 `.dockerignore`，至少排除：

```text
.git
.github
.hermes
.ai
.env
.env.*
target
logs
*.log
.ssh-exec.py
```

不得将任何生产 Secret 写入 Dockerfile、镜像 Layer 或 OCI Label。

### 5.5 平台、归属和保留策略

- 生产 NAS 已确认是 `linux/amd64`；
- 第一版只构建 `linux/amd64`，不构建 multi-arch manifest；
- 镜像写入标准 OCI source label，将 Package 明确关联到当前 GitHub 仓库，避免首次由 `GITHUB_TOKEN` 推送时出现包归属和权限继承歧义；
- GHCR 清理策略必须保留当前成功 digest、上一成功 digest和至少一个更早的已验证 digest；
- 任何镜像清理任务都必须读取部署状态，禁止仅按 Tag 年龄删除回滚基线。

---

## 6. GitHub Actions 阶段设计

### 6.1 CI 阶段

推荐步骤：

1. `actions/checkout`；
2. `actions/setup-java`，JDK 21；
3. Maven 依赖缓存；
4. 执行：

```bash
mvn -B clean verify
```

5. 构建 Docker 镜像；
6. 可选执行镜像漏洞扫描；
7. 非 PR 事件按 `linux/amd64` 构建，登录 GHCR 并同时推送 `latest`、`sha-*` 和版本 Tag；
8. 从 Buildx 输出中读取不可变 digest；
9. 将 digest、commit SHA、workflow run ID 写入 Step Summary；
10. 构建 Workflow 到此结束，不自动调用生产部署；用户打开 SSH 后通过独立 `deploy-production` Workflow 手工部署该 digest。

### 6.2 Dockerfile 构建策略

第一版可继续使用多阶段 Dockerfile，但需明确：

- CI 的 `mvn verify` 是质量门禁；
- Dockerfile 中 `mvn package -DskipTests` 只用于生成镜像内 JAR，不能替代 CI；
- 后续可优化为 CI 先构建 JAR，再由运行时 Dockerfile 复制 JAR，减少重复 Maven 构建；
- 优化构建方式不应改变业务运行语义。

### 6.3 发布证明

每次构建至少记录：

- Git commit SHA；
- GitHub workflow run ID；
- 镜像 digest；
- Maven 验证结果；
- 构建时间；
- 目标环境。

推荐使用官方 Artifact Attestation，但不把它作为第一版阻塞项。

### 6.4 Actions 供应链约束

- 优先使用 GitHub/Docker 官方 Action；
- 所有 Action 在生产 Workflow 中固定到完整 commit SHA，不能只引用可移动的主版本 Tag；
- SSH 部署优先使用系统 `ssh`，避免引入无法审计的第三方部署 Action；
- Workflow 不执行来自 Pull Request 的不可信脚本并同时持有生产 Secret；
- PR Job 与 production Job 分离，Fork PR 不获得 GHCR 写权限和生产 Environment Secret。

---

## 7. 生产服务器约束与安全边界

### 7.1 固定目录约束

不新建、不迁移现有镜像目录、Compose 目录或任何挂载目录。只在当前 Compose 所在目录增加部署所需文件：

```text
<EXISTING_COMPOSE_DIR>/
├── docker-compose.yml                 # 现有文件
├── .env                               # 现有文件
├── golden-eye-deploy.sh               # 新增
├── .golden-eye-image.env              # 新增，仅保存当前应用镜像digest
├── .golden-eye-current-image          # 新增
├── .golden-eye-previous-image         # 新增
├── .golden-eye-deploy-state           # 新增
└── .golden-eye-deploy.lock            # 新增
```

实际目录继续使用 `<EXISTING_COMPOSE_DIR>` 占位符。部署不得修改其他服务的挂载路径、数据目录和目录权限。

### 7.2 现有 NAS 用户

无法创建专用部署用户，使用当前唯一可用 NAS 用户。GitHub Production Environment 保存该用户的 SSH 私钥、临时开放的 SSH 端口及 Host Key。

部署脚本本身仍执行严格参数校验，只接受：

```text
golden-eye-deploy.sh sha256:<64位小写十六进制> <数字run-id>
```

### 7.3 脚本输入约束

必须拒绝：

- 任意仓库名；
- 可变 Tag；
- 路径字符；
- 空格、换行和 Shell 元字符；
- 多余参数；
- 非法 digest 或 run-id。

部署脚本内部固定镜像仓库：

```text
ghcr.io/<OWNER>/golden-eye
```

### 7.4 Docker 权限

群晖 Docker 路径固定使用：

```text
/usr/local/bin/docker
```

现有用户执行 Docker 必须输入 sudo 密码。密码存放在 GitHub `production` Environment Secret：

```text
DEPLOY_SUDO_PASSWORD
```

密码不能作为远端脚本参数或远端命令行文本出现。GitHub Runner 通过 SSH 标准输入把密码传给部署脚本；远端脚本第一步读取该行到仅当前 Shell 可见的变量，再通过标准输入交给 `sudo -S`：

```bash
# GitHub Runner：密码只进入ssh标准输入
printf '%s\n' "$DEPLOY_SUDO_PASSWORD" \
  | ssh -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST" \
      '<EXISTING_COMPOSE_DIR>/golden-eye-deploy.sh sha256:<DIGEST> <RUN_ID>'

# 远端 golden-eye-deploy.sh：不从命令行参数读取密码
IFS= read -r sudo_password
printf '%s\n' "$sudo_password" \
  | sudo -S -p '' /usr/local/bin/docker ...
unset sudo_password
```

Workflow 必须对 Secret 做掩码，禁止 `set -x`、命令回显和日志打印。远端脚本不得把密码写入环境文件、状态文件或日志。该方案的安全性低于专用免密 wrapper，但受现有 NAS 权限限制，是本环境的明确折中。

### 7.5 主机指纹

GitHub Actions 必须预置并校验群晖 SSH Host Key。禁止：

```text
StrictHostKeyChecking=no
```

### 7.6 SSH 生命周期

由于无法安装独立的 root 权限部署 worker 或新建系统目录，第一版部署脚本在单次 SSH Session 内同步执行。现有 Compose 目录中的状态文件用于判断上次部署停留阶段，并在下一次 SSH 开窗时继续人工恢复。

风险边界：GitHub Runner 被取消、SSH 端口提前关闭、网络中断或 NAS 重启时，远端同步脚本可能中断。部署脚本必须使用 `trap` 尽力回滚，但 `trap` 无法覆盖断电或 `SIGKILL`；因此部署期间必须保持 SSH 端口开放，直到 Workflow 明确返回 `COMMITTED` 或 `ROLLED_BACK`。

---

## 8. 部署事务状态机

### 8.1 状态

```text
RECEIVED
  → LOCKED
  → PULLING
  → DRAINING
  → WAITING_WINDOW
  → SWITCHING
  → VERIFYING
  → COMMITTED
```

失败路径：

```text
任意可恢复阶段
  → ROLLBACK_WAITING_WINDOW
  → ROLLING_BACK
  → ROLLBACK_VERIFYING
  → ROLLED_BACK
```

无法回滚：

```text
ROLLBACK_FAILED
```

### 8.2 部署前检查

1. 获取 `flock` 部署锁；
2. 校验当前没有活动事务；
3. 校验目标 digest 格式；
4. 校验目标 digest 不等于当前 digest；
5. 登录 GHCR 并拉取新镜像；
6. 执行 Compose 配置校验；
7. 确认 PostgreSQL、Redis、NapCat 容器保持运行；
8. 保存当前 digest 为回滚目标；
9. 检查磁盘空间；
10. 检查系统时钟与时区为 `Asia/Shanghai`，并确保 NTP 正常。

依赖容器基线至少记录：

```text
containerId
StartedAt
RestartCount
```

事务结束后再次比较。若依赖容器发生变化，只记录失败并告警，部署脚本不得擅自重启或“修复”它们。

### 8.3 Compose 操作边界

镜像提前拉取完成，固定在符合条件的第 15 秒发出一条应用服务重建命令：

```bash
printf '%s\n' "$sudo_password" \
  | sudo -S -p '' /usr/local/bin/docker compose \
      --project-directory <EXISTING_COMPOSE_DIR> \
      --env-file <EXISTING_COMPOSE_DIR>/.env \
      --env-file <EXISTING_COMPOSE_DIR>/.golden-eye-image.env \
      up -d --no-deps --pull never --force-recreate golden-eye
```

这里保证的是 Compose 重建命令在第 15 秒发出。Docker 内部实际发送 SIGTERM、等待旧容器退出和启动新容器的时间由优雅停机耗时决定，无法保证都发生在同一秒。采用单条重建命令是为了避免 `stop → 等待下一窗口 → up` 人为扩大停机时间。

禁止自动部署脚本执行：

```bash
docker compose down
docker compose restart
docker compose up -d                  # 未指定服务
docker compose up -d --remove-orphans
docker stop pgsql redis napcat
docker rm pgsql redis napcat
```

### 8.4 部署环境文件

Compose 中应用镜像改为：

```yaml
image: ${GOLDENEYE_IMAGE}
```

不修改现有 `.env` 中的目录和 Secret。新增 `.golden-eye-image.env`，部署事务原子写入：

```text
GOLDENEYE_IMAGE=ghcr.io/<OWNER>/golden-eye@sha256:<DIGEST>
```

写入方式必须使用临时文件 + `fsync` + 原子重命名，避免掉电时写出半个环境文件。

状态文件解析必须使用固定键和严格校验，禁止对状态文件或外部输入使用 `source`、`.` 或 `eval`。发出重建命令前先持久化 `SWITCHING` 意图状态，再执行 Docker 命令，避免 NAS 重启后出现容器已经切换、事务状态仍显示旧阶段的崩溃窗口。

---

## 9. 固定第 15 秒重建规则

### 9.1 允许条件

```text
minute % 5 != 0
second == 15
应用固定任务和已开始Bot指令已排空
```

示例：

| 时间 | 是否允许发出重建命令 |
|---|---|
| `12:05:15` | 否，分钟是 5 的倍数 |
| `12:06:15` | 是 |
| `12:07:15` | 是 |
| `12:10:15` | 否，分钟是 5 的倍数 |
| `12:06:16` | 否，已经错过本分钟固定时刻 |

### 9.2 错过时刻

拉取镜像、排空和预检都在第 15 秒之前完成。若到第 15 秒时仍有受管任务运行，则跳过当前分钟；即使任务在第 20 秒完成，也不立即重建，而是等待下一个 `minute % 5 != 0 && second == 15`。

该规则避免在任意秒补执行，部署时间可预测。它不能保证整个容器停止和启动过程不跨分钟，只保证重建命令的触发时刻固定。

### 9.3 等待伪代码

```bash
wait_for_fixed_restart_second() {
    deadline=$(( $(date +%s) + MAX_WINDOW_WAIT_SECONDS ))

    while [ "$(date +%s)" -lt "$deadline" ]; do
        minute=$(date +%M)
        second=$(date +%S)
        minute=$((10#$minute))
        second=$((10#$second))

        if [ $((minute % 5)) -ne 0 ] && [ "$second" -eq 15 ]; then
            return 0
        fi

        sleep 1
    done

    return 1
}
```

回滚同样等待下一个符合条件的第 15 秒，再通过同一条 Compose 重建命令恢复上一 digest。群晖 Shell 兼容性需要在实施时实机验证；不能假设所有 BusyBox `sh` 行为与 Bash 相同。

---

## 10. 应用任务排空设计

### 10.1 状态模型

新增 JVM 内部署生命周期协调器：

```text
STARTING
  → ACCEPTING
  → DRAINING
  → STOPPING
```

含义：

- `STARTING`：Spring Context 已启动，但关键初始化尚未完成；
- `ACCEPTING`：正常接收 Bot 指令和定时任务；
- `DRAINING`：拒绝新的业务入口，等待已开始任务完成；
- `STOPPING`：应用关闭中。

当前是单实例部署，状态只需保存在 JVM 内，不引入 Redis/数据库分布式锁。

### 10.2 受管任务统计

协调器维护：

```text
activeTasks       当前正在执行的受管顶层任务数
acceptedTotal     启动以来接受数
rejectedTotal     排空后拒绝数
state             当前生命周期状态
lastStateChange   最近状态变化时间
activeTaskNames   可选：任务名及开始时间，仅用于诊断
```

核心 API：

```java
boolean tryEnter(String taskName);
void leave(String taskName);
boolean beginDraining();
boolean awaitDrained(Duration timeout);
DeploymentRuntimeSnapshot snapshot();
```

`tryEnter()` 与 `beginDraining()` 必须通过同一把锁或等价原子协议保证线性化，禁止以下竞态：

```text
线程A看到 ACCEPTING
线程B切到 DRAINING 并看到 activeTasks=0
线程A随后 activeTasks++ 并开始新任务
```

### 10.3 顶层任务与子任务口径

推荐统计口径：

- 每个外部触发入口只登记一个顶层任务；
- 顶层方法内部通过 `CompletableFuture` 创建并 `join()` 的子任务不重复计数；
- fire-and-forget 子任务必须纳入共享执行器的关闭等待，或者改为显式受管提交；
- Bot 指令以一次消息处理为一个顶层任务；
- 固定 `@Scheduled` 以一次方法调用为一个顶层任务；
- 动态任务不登记、不等待，由重启后的现有补偿机制负责。

这样避免每个子任务重复计数，同时保证顶层任务只有在所有已 `join()` 子任务完成后才离开。

### 10.4 Bot 新请求门禁

`BotSocketClient` 在 `DRAINING` 后仍处理服务端心跳和非业务事件。新的群聊/私聊指令不进入原 Strategy，也不登记 activeTasks，而是立即回复统一维护提示：

```text
系统正在更新重启，请稍后再试
```

已开始处理的指令继续执行完毕并正常回复。维护回复必须复用现有 `BotReplyService` / 消息 Builder，不直接调用新的外部接口。若回复本身失败，只记录不含消息敏感内容的警告日志，不阻塞排空。

不采用静默丢弃，避免用户误以为 Bot 没有收到请求；第一版也不区分查询/写入指令，否则需要为全部 Strategy 增加只读分类，扩大改造范围。

### 10.5 `@Scheduled` 门禁

现有 5 个 `@Scheduled` 方法统一通过轻量包装执行：

```java
managedTaskRunner.runIfAccepting("stock-minute-collect", this::doSpiderStockData);
```

实施时应拆分入口和真实业务方法，避免调度代理、自调用和测试语义混乱：

```java
@Scheduled(...)
public void spiderStockData() {
    managedTaskRunner.runIfAccepting("stock-minute-collect", this::executeSpiderStockData);
}
```

排空后新触发的 Cron 任务直接跳过，不进入 activeTasks。

### 10.6 动态任务边界

动态任务明确不接入 `DeploymentLifecycleCoordinator`：

- 不修改 `DynamicTaskService`；
- 不取消未开始的动态任务；
- 不统计正在执行的动态任务；
- 容器重启造成的漏执行或中断，继续由各业务现有 `ApplicationReadyEvent` 和持久化时间标记补偿；
- 自动部署验收只验证固定 Cron 和 Bot 排空，不把动态任务 activeTasks 作为切换门禁。

这是一项已确认的业务取舍：缩小改造面，并接受动态任务可能在容器切换时中断。

### 10.7 共享异步执行器

`virtualThreadExecutor` 配置建议：

```java
setWaitForTasksToCompleteOnShutdown(true);
setAwaitTerminationSeconds(<ASYNC_AWAIT_SECONDS>);
setStrictEarlyShutdown(true);
```

并将执行器销毁职责交给 Spring 容器。`BotSocketClient.shutdown()` 不应直接关闭共享 `virtualThreadExecutor`，否则一个组件会提前终止全局共享资源。

对于 fire-and-forget 提交：

- `DRAINING` 后拒绝新的业务入口可阻止大多数新提交；
- 已经排队/运行的任务由执行器关闭等待兜底；
- 长期建议新增统一 `ManagedTaskExecutor` 门面，逐步替代业务类直接调用 `virtualThreadExecutor.execute()`。

### 10.8 活跃度专用执行器

`activityCollectExecutor` 使用 `SimpleAsyncTaskExecutor`，实施时需要配置任务终止等待，或改为 Spring 可控的受管执行器。活跃度顶层任务本身会等待 `CompletableFuture.allOf(...).join()`，只要顶层 `collectActivity()` 被正确登记，正常情况下排空能等待整个批次完成。

### 10.9 启动补偿任务

大量 `ApplicationReadyEvent` 会立即补刷遗漏数据。新容器 readiness 不能在这些关键初始化完成前返回 UP，否则可能出现：

```text
新容器 health=UP
流水线提交部署成功
随后启动补偿任务失败或长时间阻塞
```

建议新增启动协调器：

- Spring 基础启动完成时保持 `STARTING`；
- 对关键生产初始化入口登记 startup task；
- 所有关键初始化完成后进入 `ACCEPTING`；
- 只做常量加载、缓存准备且不会影响生产可用性的初始化可不阻塞 readiness；
- 初始化失败时 readiness 保持 DOWN，并输出不含敏感信息的原因。

实施前需要逐一归类 15 个 `ApplicationReadyEvent` 监听器，不能机械地全部设为阻塞，也不能全部忽略。

---

## 11. 排空控制接口与安全

### 11.1 Actuator

引入：

```xml
<artifactId>spring-boot-starter-actuator</artifactId>
```

暴露最小端点：

```text
/actuator/health/liveness
/actuator/health/readiness
```

readiness 由自定义部署健康组件参与判断；版本和任务快照从内部部署状态端点读取：

```text
health/readiness：只返回 UP/DOWN 和必要的非敏感状态
deployment：返回 state、activeTasks、version、commit、imageDigest
```

健康详情不得输出数据库、Redis、NapCat、飞书或 Torn API Secret。

### 11.2 管理端口

推荐单独管理端口：

```yaml
management:
  server:
    port: ${MANAGEMENT_SERVER_PORT}
  endpoints:
    web:
      exposure:
        include: health,deployment
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
      group:
        readiness:
          include: readinessState,db,redis,deployment
```

管理端口不通过 Compose `ports` 发布到公网。部署脚本通过 `docker exec golden-eye` 在容器内部访问 `127.0.0.1:${MANAGEMENT_SERVER_PORT}`；Docker healthcheck 同样在容器内部访问该端口。

### 11.3 排空控制方式

推荐实现自定义管理端点：

```text
POST /actuator/deployment
GET  /actuator/deployment
```

写操作请求体：

```json
{
  "action": "drain",
  "token": "<DEPLOY_CONTROL_TOKEN>"
}
```

鉴权使用随机生成的 `DEPLOY_CONTROL_TOKEN`，通过环境变量注入。部署脚本不从宿主机展开真实 Token，而是在容器内执行 Shell，让容器自己的环境变量完成展开。具体 Spring Boot 3.5.16 Actuator `@Endpoint` / `@WriteOperation` 入参方式必须在实施时以实际 API 编译验证为准。

调用形式示意：

```bash
/usr/local/bin/docker exec golden-eye sh -lc \
  'curl --fail --silent --request POST \
    --header "Content-Type: application/json" \
    --data "{\"action\":\"drain\",\"token\":\"$DEPLOY_CONTROL_TOKEN\"}" \
    "http://127.0.0.1:$MANAGEMENT_SERVER_PORT/actuator/deployment"'
```

端点必须：

- 使用常量时间比较 Token；
- Token 缺失或错误返回 401/403；
- 不记录 Token；
- `drain` 幂等；
- 仅允许从管理网络访问；
- 不提供恢复 `ACCEPTING` 的远程接口。排空后如果放弃本次部署，原则上重启当前镜像恢复，避免在半排空状态继续服务。

状态读取端点不返回 Secret。即使管理端口未发布，仍保留 Token 校验作为纵深防御；生产日志禁止记录写操作请求体。

### 11.4 排空超时

推荐初始值：

```text
DRAIN_TIMEOUT_SECONDS=180
ASYNC_AWAIT_SECONDS=180
SPRING_SHUTDOWN_TIMEOUT=200s
DOCKER_STOP_GRACE_PERIOD=220s
```

关系必须满足：

```text
Docker stop grace
  > Spring shutdown phase timeout
  >= executor await timeout
```

具体值在首次压测后调整，不应凭空缩短。

排空超时默认 fail-closed：

- 不停止容器；
- 标记部署失败；
- 保留新镜像供下次部署；
- 输出仍在运行的任务名和持续时间；
- 不使用 `SIGKILL` 强行推进正常发布。

紧急停机属于独立人工运维流程，不属于自动部署。

---

## 12. 优雅停机

Spring Boot 3.5 默认启用 Web 服务器优雅停机。仍需显式配置超时，便于审计：

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: ${SPRING_SHUTDOWN_TIMEOUT:200s}
```

Compose：

```yaml
stop_signal: SIGTERM
stop_grace_period: 220s
```

关闭顺序建议：

1. 远程调用 `drain`；
2. 等待固定 `@Scheduled` 任务和已开始 Bot 指令的 `activeTasks=0`；
3. 动态任务不等待；
4. 等待非 5 倍数分钟的第 15 秒；
5. 发出单条 Compose `up --force-recreate`，Docker 向旧容器发送 SIGTERM；
6. Spring 停止接收新 Web 请求；
7. Spring 关闭共享执行器并等待剩余异步任务；
8. Bot WebSocket 停止重连并关闭；
9. 超时后 Docker 才允许强制终止。

`BotSocketClient` 的两个 `ScheduledExecutorService` 都需要关闭。当前只关闭 `connectionMonitor`，还应检查并关闭 `reconnectScheduler`。

---

## 13. 健康检查与部署成功标准

### 13.1 Docker 健康检查

`golden-eye` 增加：

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl --fail --silent http://127.0.0.1:$${MANAGEMENT_SERVER_PORT}/actuator/health/readiness || exit 1"]
  interval: 10s
  timeout: 3s
  retries: 18
  start_period: 30s
```

当前运行镜像中已验证存在 `curl`，但正式实施仍应将健康检查依赖作为 Dockerfile 的显式契约，而不是依赖基础镜像偶然携带。

### 13.2 Readiness 条件

至少满足：

- Spring Context 启动完成；
- Liquibase 完成；
- PostgreSQL 可用；
- Redis 可用；
- 应用状态为 `ACCEPTING`；
- 关键启动初始化完成；
- 当前不是 `DRAINING/STOPPING`。

Liveness 只判断 JVM/应用自身是否仍能运行，不加入 PostgreSQL、Redis 或外部 API，避免依赖短暂故障引发容器重启风暴。PostgreSQL、Redis 和部署状态只进入 readiness。

NapCat/Torn/飞书等外部服务是否进入 readiness，需要按故障容忍度分类：

- 如果不可用会让整个 Bot 完全不可服务，则纳入 readiness；
- 如果只是部分功能降级，不应导致容器无限重启，应通过独立 HealthIndicator 标记详情和告警。

### 13.3 成功门禁

部署成功必须同时满足：

1. 容器状态 `running`；
2. Docker health 为 `healthy`；
3. readiness 连续成功至少 3 次；
4. 应用报告的 commit/digest 与目标一致；
5. PostgreSQL、Redis、NapCat 容器 ID 未变化且仍运行；
6. 新容器在观察期内没有再次启动；
7. 启动日志不存在明确的 Liquibase 或 Spring 启动失败。

推荐观察期：

```text
POST_START_STABILITY_SECONDS=60
```

---

## 14. 自动回滚

### 14.1 回滚触发

以下任一情况触发回滚：

- 新容器无法创建；
- 新容器启动后退出；
- readiness 在超时内未成功；
- commit/digest 不匹配；
- 稳定观察期内重启；
- 依赖容器被意外影响；
- 部署事务脚本检测到状态不一致。

### 14.2 回滚流程

1. 保存失败容器日志和 inspect 信息；
2. 等待下一个非 5 倍数分钟的第 15 秒；
3. 将 `GOLDENEYE_IMAGE` 原子恢复为上一 digest；
4. 只重建 `golden-eye`；
5. 等待旧镜像 readiness；
6. 旧镜像恢复成功则状态为 `ROLLED_BACK`；
7. 恢复失败则状态为 `ROLLBACK_FAILED`，立即停止自动重试并要求人工处理。

### 14.3 数据库迁移边界

这是自动回滚最重要的限制：

```text
应用镜像回滚 ≠ 数据库自动回滚
```

Liquibase 变更必须遵守向后兼容的 Expand/Contract：

1. 先增加新表/新列/新索引；
2. 新旧代码都能运行；
3. 数据回填独立执行；
4. 观察稳定后再删除旧结构；
5. 删除列、改类型、重命名等破坏性变更不得与普通自动发布绑定。

如果某版本包含不能向后兼容的 Schema 变更：

- 禁止自动回滚旧镜像；
- 必须走独立维护窗口、备份和人工审批；
- Workflow 应通过显式发布标志阻止普通自动部署。

---

## 15. 首次上线 Bootstrap

自动流水线首次启用前必须人工完成：

1. 创建私有 GHCR Package；
2. 检查当前生产容器实际引用的 image ID；
3. 将该实际镜像标记并推送到私有 GHCR，取得 RepoDigest；
4. 将其写入 `current_successful_digest` 和 `previous_successful_digest`；
5. 保留本地镜像，并可额外保存受控的 `docker save` 离线归档；
6. 验证该 digest 可以在 NAS 上重新拉取；
7. 群晖登录 GHCR 并测试只读拉取；
8. 在现有 Compose 目录增加部署脚本、镜像环境文件、状态文件和锁文件；
9. 配置现有 NAS 用户的 SSH Key；
10. 配置 SSH Host Key、临时 SSH 端口和 sudo 密码到 GitHub `production` Environment；
11. 将当前 Compose 镜像切换为 digest 变量引用；
12. 部署固定 Cron/Bot 排空和健康检查版本；
13. 做一次人工部署演练；
14. 做一次故意健康失败的自动回滚演练；
15. 确认依赖容器全程未重启；
16. 确认 Workflow 完成前 SSH 端口不会关闭；
17. 自动部署 Workflow 最后启用，但仍保持手工触发。

不能只根据旧 commit 重新构建并假设它等于当前生产镜像；构建时间、基础镜像和依赖变化都可能产生不同结果。Bootstrap 应以当前实际运行容器引用的 image ID 为基线。

Bootstrap 前没有“上一 GHCR digest”时，不得声称自动回滚可用。

---

## 16. 文件级实施清单

### 16.1 新增文件

```text
.github/workflows/ci.yml
.github/workflows/deploy-production.yml
.dockerignore
build/scripts/golden-eye-deploy.sh
src/main/java/pn/torn/goldeneye/configuration/deployment/DeploymentLifecycleCoordinator.java
src/main/java/pn/torn/goldeneye/configuration/deployment/DeploymentLifecycleState.java
src/main/java/pn/torn/goldeneye/configuration/deployment/DeploymentTaskRunner.java
src/main/java/pn/torn/goldeneye/configuration/deployment/DeploymentEndpoint.java
src/main/java/pn/torn/goldeneye/configuration/deployment/DeploymentHealthIndicator.java
src/main/java/pn/torn/goldeneye/configuration/deployment/DeploymentProperties.java
src/test/java/pn/torn/goldeneye/configuration/deployment/DeploymentLifecycleCoordinatorTest.java
src/test/java/pn/torn/goldeneye/configuration/deployment/DeploymentTaskRunnerTest.java
src/test/java/pn/torn/goldeneye/configuration/deployment/DeploymentEndpointTest.java
build/scripts/tests/deploy-window-test.sh
build/scripts/tests/deploy-state-machine-test.sh
```

`DeploymentEndpoint` 的具体 Spring Actuator 扩展类型在实施时按 Spring Boot 3.5.16 API 确认，禁止凭记忆使用不存在或已废弃的 API。

### 16.2 修改文件

```text
pom.xml
build/Dockerfile
build/docker-compose.yml
src/main/resources/application.yml
src/main/java/pn/torn/goldeneye/configuration/CommonConfiguration.java
src/main/java/pn/torn/goldeneye/configuration/RedisConfig.java
src/main/java/pn/torn/goldeneye/configuration/socket/BotSocketClient.java
src/main/java/pn/torn/goldeneye/torn/manager/torn/stocks/TornStocksManager.java
src/main/java/pn/torn/goldeneye/torn/manager/vip/VipSubscribeManager.java
src/main/java/pn/torn/goldeneye/torn/manager/vip/VipNoticeManager.java
src/main/java/pn/torn/goldeneye/torn/service/activity/TornActivityCollectService.java
src/main/java/pn/torn/goldeneye/torn/service/user/TornUserStateService.java
README.md
.ai/knowledge/file_location.md
```

### 16.3 需要审计但不一定首轮全部修改

直接使用 `virtualThreadExecutor` 的业务类需逐一判断是否为顶层入口、同步子任务或 fire-and-forget：

```text
BindKeyStrategyImpl
AttackSyncStrategyImpl
TornFactionOcBenefitService
TornBaseDataService
TornStocksManager
TornFactionOcManager
以及其余 ThreadPoolTaskExecutor / CompletableFuture 调用点
```

首轮必须覆盖所有“可能在排空开始后继续接收新业务”的入口；同步内部子任务不应机械重复包装。

---

## 17. 分阶段实施与审批点

### 阶段 A：本地基础设施与应用排空

- 新增生命周期协调器；
- 接入固定 Cron 和 Bot 指令入口，动态任务保持原状；
- 配置执行器优雅关闭；
- 新增 Actuator readiness 和 drain 接口；
- 增加单元测试和本地集成测试。

**审批要求**：用户明确回复“开始实施阶段 A”。

### 阶段 B：CI 和镜像发布

- 新增 GitHub Actions；
- 配置私有 GHCR；
- 镜像 digest 和构建证明；
- 不连接生产服务器。

**审批要求**：用户明确回复“开始实施阶段 B”。

### 阶段 C：群晖部署脚本与安全初始化

- 将部署脚本和状态文件安装到现有 Compose 目录；
- 配置现有 NAS 用户、SSH Key、sudo 密码和 Host Key；
- 配置 GHCR 只读凭据；
- 在现有 Compose 目录初始化镜像状态文件和部署锁；
- 不开启合并后无人值守部署。

**审批要求**：单独确认生产服务器初始化。

### 阶段 D：人工演练

- 当前版本 digest Bootstrap；
- 成功部署演练；
- 失败健康检查回滚演练；
- 断网、排空超时、并发部署演练；
- 核对依赖容器没有重启。

**审批要求**：单独确认生产演练。

### 阶段 E：启用自动部署

- 打开手工 `workflow_dispatch` 生产部署；
- 用户每次先打开 SSH 端口，再触发 Workflow；
- 部署完成后关闭 SSH 端口；
- 不将生产部署绑定到代码合并自动触发。

**审批要求**：单独确认启用自动生产部署。

---

## 18. 测试方案

### 18.1 Java 单元测试

生命周期协调器：

1. `ACCEPTING` 时任务可进入；
2. `DRAINING` 后新任务被拒绝；
3. 已进入任务完成后 activeTasks 归零；
4. `beginDraining()` 与 `tryEnter()` 并发时不存在漏计任务；
5. 重复 drain 幂等；
6. activeTasks 不会变为负数；
7. 任务抛异常也必须 leave；
8. 超时返回正确状态和活动任务快照。

固定调度入口：

1. 正常状态执行真实逻辑；
2. 排空状态跳过；
3. 业务异常不泄漏 activeTasks；
4. 活跃度 `collecting` 防重入与部署门禁协同正确。

动态任务边界：

1. 排空前后 `DynamicTaskService` 行为保持不变；
2. 不登记 activeTasks；
3. 不取消未开始任务；
4. 通过现有测试或结构断言证明本功能未修改动态任务；
5. 选取一个已有补偿任务验证重启后仍按原机制补偿。

Bot：

1. 排空后心跳仍处理；
2. 排空后新的群聊/私聊指令收到统一“系统正在更新重启，请稍后再试”回复；
3. 排空后不进入原业务 Strategy；
4. 已开始指令继续完成并回复；
5. 正常状态行为不变；
6. 关闭时不由 Bot 组件提前关闭共享执行器。

### 18.2 Shell 时间窗测试

使用可注入时钟，覆盖：

| 时间 | 期望 |
|---|---|
| `12:05:15` | 禁止，分钟为5倍数 |
| `12:06:14` | 禁止，尚未到固定秒 |
| `12:06:15` | 允许 |
| `12:06:16` | 禁止，已错过固定秒 |
| `12:09:15` | 允许，即使操作可能跨到下一分钟 |
| 到15秒仍未排空 | 跳过当前分钟 |
| 时钟倒退 | fail-closed |
| 超过最大等待 | 部署失败，不切换 |

### 18.3 部署状态机测试

1. 拉取失败：旧容器不受影响；
2. 排空超时：旧容器不停止；
3. 新容器创建失败：恢复环境文件；
4. readiness 超时：回滚旧 digest；
5. 回滚成功：状态 `ROLLED_BACK`；
6. 回滚失败：停止自动重试；
7. 两次部署并发：后者不能取得锁；
8. SSH 中断：状态文件保留最后阶段，下次开窗时能够识别并人工恢复；
9. NAS 重启后存在陈旧事务：脚本拒绝盲目开始新部署并输出恢复提示；
10. PostgreSQL、Redis、NapCat 容器 ID 在部署前后保持不变。

### 18.4 本地验证命令

实施后至少执行：

```bash
JAVA_HOME="C:\\Program Files\\Java\\jdk-21" mvn.cmd clean verify
```

Docker 配置验证：

```bash
docker compose -f build/docker-compose.yml config
```

镜像构建：

```bash
docker build -f build/Dockerfile -t golden-eye:deployment-test .
```

本地集成演练应使用隔离 Compose Project，不连接生产数据库和生产 Redis。

---

## 19. 运维告警与审计

每次部署记录：

- workflow run ID；
- commit SHA；
- 目标和上一镜像 digest；
- 使用的 SSH 端口（不记录私钥或密码）；
- 请求、排空开始、排空完成、第15秒重建、健康成功时间；
- 排空时 activeTasks；
- 部署结果；
- 回滚结果；
- 失败阶段和错误摘要。

禁止记录：

- SSH 私钥；
- GHCR Token；
- `DEPLOY_CONTROL_TOKEN`；
- 数据库、Redis、NapCat、飞书或 Torn API 密钥；
- 完整 `.env`。

告警至少覆盖：

- 部署失败；
- 自动回滚；
- 回滚失败；
- 排空超时；
- readiness 长时间失败；
- 连续容器重启；
- 磁盘空间不足；
- 生产时钟/NTP 异常。

---

## 20. 风险与权衡

### 20.1 不是零停机

当前是单实例停机重建，会存在几十秒不可用窗口。任务排空解决数据一致性和任务中断，不提供零停机。

### 20.2 排空只能覆盖已纳管任务

项目中存在较多直接异步提交。实施时如果漏掉 fire-and-forget 入口，activeTasks 归零不一定代表所有后台任务结束。因此执行器关闭等待和调用点审计都是硬门禁。

### 20.3 长任务可能阻塞发布

排空默认 fail-closed。长时间固定 Cron、已开始 Bot 指令或固定任务关联的 API 卡顿可能让发布失败。动态任务不参与排空，不会阻塞切换；其漏执行或中断风险由现有启动补偿承担。固定受管任务应通过任务超时、可恢复进度解决，不能通过缩短 Docker grace 强杀。

### 20.4 sudo 密码进入 GitHub Secret

当前 NAS 权限不允许新建用户或配置专用免密 wrapper，因此必须将 sudo 密码保存到 GitHub `production` Environment Secret。即使通过 `sudo -S` 标准输入传递，仍比专用部署用户方案风险更高；必须启用 Environment 权限限制、Secret 掩码，禁止 `set -x`，并定期轮换密码。

### 20.5 临时 SSH 与同步事务

生产部署依赖用户临时开放 SSH 端口。Workflow 无法自动打开或关闭端口，且同步 SSH 中断可能留下 `SWITCHING/VERIFYING` 状态。用户必须等待 Workflow 明确结束后再关闭端口；下次部署先检查状态文件，不得盲目覆盖。

### 20.6 Liquibase 不可逆变更

普通镜像自动回滚不能处理破坏性 Schema 迁移。数据库变更必须独立审批并遵循向后兼容发布。

### 20.7 外部依赖健康口径

把所有外部 API 都纳入 readiness 可能造成无意义回滚；完全不纳入又可能发布一个不可用 Bot。实施时必须按核心依赖和可降级依赖分类。

### 20.8 明文敏感配置

当前项目配置中存在明文敏感值的风险。自动部署上线前应将所有敏感项改为环境变量占位符并轮换已暴露值；Git 历史清理需单独评估，不能在本方案中自动执行。

---

## 21. 验收标准

只有全部满足才视为方案实施完成：

- [ ] PR 会执行 Maven 全量验证；
- [ ] 合并代码只构建和推送镜像，不连接 NAS；
- [ ] 同时发布 `latest` 和不可变 digest；
- [ ] 生产镜像位于私有 GHCR；
- [ ] 生产按 digest 部署；
- [ ] GitHub Actions 生产部署串行化；
- [ ] 排空后新的 Bot 指令收到明确维护回复；
- [ ] 排空后不再开始新的固定定时任务；
- [ ] 动态任务未被修改并继续使用启动补偿；
- [ ] 已开始受管任务会被等待；
- [ ] 共享和专用执行器具备关闭等待；
- [ ] 重建命令只在非5倍数分钟的第15秒发出；
- [ ] 到第15秒未排空时会跳过当前分钟；
- [ ] 仅重建 `golden-eye`；
- [ ] readiness 能验证目标版本；
- [ ] 健康失败可恢复上一 digest；
- [ ] 回滚演练通过；
- [ ] PostgreSQL、Redis、NapCat 在演练中未重启；
- [ ] sudo 密码只通过 GitHub Secret 和标准输入传递，日志不泄露；
- [ ] 部署期间 SSH 端口保持开放，完成后由用户关闭；
- [ ] 日志没有 Secret；
- [ ] 数据库破坏性迁移不会进入普通自动部署。

---

## 22. 参考资料

- GitHub：Publishing Docker images  
  https://docs.github.com/en/actions/use-cases-and-examples/publishing-packages/publishing-docker-images
- GitHub：Configuring a package's access control and visibility  
  https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility
- GitHub：Control workflow concurrency  
  https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency
- GitHub：Manage environments  
  https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
- Docker Compose services：`healthcheck`、`stop_grace_period`  
  https://docs.docker.com/reference/compose-file/services/
- Spring Boot 3.5：Graceful Shutdown  
  https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html
- Spring Boot 3.5：Actuator Endpoints and Health Probes  
  https://docs.spring.io/spring-boot/reference/actuator/endpoints.html

---

## 23. 最终结论

推荐采用：

> **GitHub Actions 全量验证 → 私有 GHCR 同时发布 `latest` 与不可变 digest → 用户临时打开 SSH → 手工触发部署 → 固定 Cron/Bot 排空 → 非5倍数分钟第15秒单服务重建 → readiness 连续验证 → 自动提交或回滚。**

其中固定第 15 秒和任务排空解决的是两类不同风险：

- 固定第 15 秒避免在已知 Cron 的第 0、5、10 秒触发点发出重建命令，并让部署时刻可预测；
- 任务排空证明固定 Cron 和已开始 Bot 指令已经完成；动态任务不参与该证明，依赖启动补偿。

二者按本方案同时使用。
