# torn-golden-eye

Chat Bot program in QQ Group\
\
QQ群聊天机器人金眼，基于Torn的部分帮派群定制功能

## 当前技术栈

| 名称           | 版本      | 作用        | 集成状态 | 
|:-------------|:--------|:----------|------|
| JDK          | 21      | 语言能力      | 已集成  |
| SpringBoot   | 3.5.3   | 基础框架      | 已集成  |
| NapCat       | 4.8.119 | 机器人框架     | 已集成  |
| Docker       | 28.3.0  | 容器        | 已集成  |
| Tyrus        | 2.1.5   | Websocket | 已集成  |
| Logback      | 1.5.18  | 日志框架      | 已集成  |
| Loki         | 3.5.2   | 日志采集      | 已集成  |
| Grafana      | 12.0.2  | 监控平台      | 已集成  |
| PostgreSql   | 17.5    | 数据库       | 已集成  |
| Liquibase    | 4.31.0  | 数据库迭代     | 已集成  |
| Mybatis Plus | 3.5.12  | ORM       | 已集成  |

## 启动方式

1. 进入项目所在的文件夹,运行以下代码打包镜像

```
docker build -f ./build/Dockerfile -t golden-eye:0.3.0 .
```

2. 进入build文件夹,修改.env文件
3. 控制台运行以下命令用来启动容器:

```
docker-compose up -d --scale golden-eye=0
```

4. 控制台运行如下代码，然后使用手机QQ扫码登录

```
docker logs napcat
```
5. 手动创建数据库`golden-eye`

6. 控制台运行以下命令启动金眼

```
docker compose up -d golden-eye
```

7. 浏览器输入localhost:16699，打开NapCat的WebUI，输入默认Token`napcat`登录WebUI

## 常见问题排查

> 镜像打包失败

- 请确认工作目录是否正确，应当同时能看到src、build两个文件夹和pom.xml文件
- 网络原因，需要科学上网，如果连接不稳定可以将源镜像先pull到本地再运行
- ```
  docker pull maven:3.9.10-amazoncorretto-21
  docker pull openjdk:21-jdk-slim
  ```

> 请求Torn Api失败

- 请确认是否在`torn_api_key`表中插入了可用的api key数据，多条数据时会根据当天最少的次数按序请求

> 请求飞书 Api失败

- 请确认是否配置了飞书的环境变量，IDEA启动时需要在项目配置中设置，镜像启动时需要替换`.env`文件中的变量