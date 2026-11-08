# 可选 Redis 共享会话

2026-11-08，C2。默认 `LIANZHI_REDIS_ENABLED=false`，继续使用原本的进程内 Session；添加依赖本身不会开启 Redis。显式启用后使用 Spring Session Redis，保留现有表单登录、`JSESSIONID`、HttpOnly、SameSite=Lax、CSRF 和每次请求的账号版本检查。

依赖由现有 Spring Boot3.5.16 BOM 管理：Spring Session3.5.7、Spring Data Redis3.5.13、Lettuce6.6.0.RELEASE；Java17要求和原框架不变。按 [Boot3.5说明](https://docs.spring.io/spring-boot/3.5/reference/web/spring-session.html)排除自动选择的会话/Redis配置，使用条件配置显式创建本功能需要的连接与过滤器，避免本机未部署Redis时被自动启用。使用基本 `RedisSessionRepository`，不要求订阅过期事件或修改Redis全局CONFIG。

## 配置与启动

复制既有 `deploy/c-mysql-ai.env.example` 到忽略目录的本机配置，填写Redis连接。全部实例共享同一个MySQL、Redis数据库和命名空间，并使用同一构建版本。开关或命名空间变更按一次会话切换处理，不能让本地Session模式和Redis模式混合接收同一网站请求。

| 环境变量 | 默认/含义 |
| --- | --- |
| `LIANZHI_REDIS_ENABLED` | false；必须显式设为true才共享会话 |
| `LIANZHI_REDIS_HOST` / `PORT` | 127.0.0.1 / 6379；主机名与端口，非带凭据URL |
| `LIANZHI_REDIS_DATABASE` | 0，非负数据库编号；本次支持单机Redis |
| `LIANZHI_REDIS_USERNAME` / `PASSWORD` | 可选ACL用户名/密码；在本机填写，不放入命令行或Git |
| `LIANZHI_REDIS_TLS` | false；true使用TLS及默认服务器证书校验，私有CA加入JVM信任库，不提供跳过校验选项 |
| `LIANZHI_REDIS_NAMESPACE` | `lianzhi:session:v1`，1～100位字母、数字、冒号、横线或下划线；按环境隔离 |
| `LIANZHI_REDIS_CONNECT_TIMEOUT` / `COMMAND_TIMEOUT` | 各1s，可配100ms～10s |
| `LIANZHI_SESSION_TIMEOUT` | 30m，沿用server.servlet.session.timeout；必须为正时长 |
| `SESSION_COOKIE_SECURE` | 沿用原配置；HTTPS设true，本地HTTP验证使用false |

```powershell
& artifacts/c-media-venv/Scripts/python.exe tools/c-package/run-full.py --mysql --env-file artifacts/c-mysql-ai.env
```

此入口启动Java与已有本地AI服务，连接已运行的Redis，不安装或接管Redis服务。A原有 `start-ai.cmd` / 私有SDK加载方式保持原样；从其上下文继承上述变量也可启用共享会话。数据库、Redis、SDK凭据只传给Java子进程，不传给Ollama或媒体推理子进程。

Linux同样支持通过环境变量或 `--env-file` 启动。Redis可以运行在Linux/WSL/容器或已有的可信私网服务上，Windows Java后端只需能访问配置的地址。不要把裸Redis端口开放到公网。

## 认证与序列化

- 仍由Spring Security表单认证读取数据库，角色/密码/公司/停用通过数据库auth_version撤销旧会话。共享登录不会跳过 `AccountSessionFilter`，也不会把请求中的userId当身份。
- 登录旋转会话ID；另一实例可读取登录态和CSRF令牌；退出删除共享会话，其他实例随后拒绝旧Cookie。Cookie名和原注销逻辑一致，Secure依据现有设置。
- 采用Spring Session默认的Java序列化，见 [Spring Session3.5配置说明](https://docs.spring.io/spring-session/reference/3.5/configuration/redis.html)。`SessionUser`有明确serialVersionUID，认证密码在缓存前由原认证流程清除；不保存UserAccount持久层对象或密码散列。
- 确认草稿使用C专属版本化快照，保持业务DTO/迁移不变；修改、取消、提交状态都显式setAttribute，符合ON_SET_ATTRIBUTE保存模式。真实Redis内容已检查不含登录密码、密码散列或UserAccount对象。
- Java序列化只用于可信Redis数据。同一命名空间要求兼容构建；后续更改序列化结构时升级命名空间并让用户重新登录，不承诺任意版本混合滚动升级兼容。

## 申请确认与故障语义

Spring Session中的对象锁不能跨JVM使用。Redis模式额外为每份确认建立有TTL的原子状态，覆盖OPEN、处理中、DONE、CANCELLED；取消和确认不能同时宣称成功，旧会话副本也不能恢复已取消草稿。仍须用户明确确认、重新检查身份/范围，再调用A正常事务服务；MySQL requestKey是最终业务防重依据。Redis关闭时保留原本机确认流程。

确认状态最长10分钟，和既有确认有效期一致。业务校验失败只释放本次持有的处理中标记；MySQL已提交后，Redis确认失败不会释放标记或伪称回滚。若进程在提交中断开，先查询我的申请；处理中标记可能保留至原确认到期，不能自动新建申请重试。Redis与MySQL没有分布式事务，也不声称具有Exactly Once提交能力。

显式Redis模式启动时执行真实PING；不可达或认证失败会中止启动。运行中读写/反序列化失败返回503、`SESSION_STORE_UNAVAILABLE`、data=null，不回退为本机登录态，不暴露服务诊断或凭据。已有Cookie保留用于服务恢复。Redis恢复且数据仍在时可继续会话；数据丢失/命名空间变更时需重新登录。业务响应未知时先查询实际申请，不自动重新提交。

账号版本以MySQL为准；Redis缓存不能扩大公司或用户范围。使用共享会话的机器需保持时钟同步，存储的空闲间隔及实际Redis键过期分别验证。

## 可选容器部署

在 `deploy/.env` 填写MySQL、管理员和Redis密码，使用：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.redis.yaml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.redis.yaml up -d --build
```

附加配置使用 [官方镜像](https://raw.githubusercontent.com/docker-library/official-images/master/library/redis)中的固定 `redis:7.4.12-alpine`，不映射Redis宿主机端口；Java容器通过内部服务名redis连接。Redis使用独立数据卷、密码认证、256MB/noeviction、AOF和appendfsync always。启动脚本将密码转换为ACL散列后写入临时配置，不把明文放在进程参数。停止使用同样的Compose前缀加stop；不要使用down -v删除现有卷。

[Redis持久化说明](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)解释了AOF/RDB差异。这里验证正常停止/重启后的会话保留，不代表断电、磁盘故障或高可用验收；Redis备份也包含认证会话，不能公开分发。完整部署和备份恢复仍按C4继续完成。

## 可复现验证

Linux/Docker环境：

```sh
python scripts/verify-c-redis.py --mysql-bin /usr/bin --redis-image redis:7.4.12-alpine --java "$JAVA_HOME/bin/java" --jar backend/target/lianzhi-backend-0.1.0.jar
```

脚本创建全新目录的MySQL实例、独占Redis容器和独立Java进程；Windows可改用 `--redis-server <WSL中的Redis路径> --wsl-distro Ubuntu --redis-library-directory <WSL动态库目录>`，或Linux直接传本机Redis可执行文件。它不会连接已有业务库、调用FLUSHDB或停止系统服务。需要MySQL服务端/客户端程序、Redis程序或Docker和Java；默认不下载模型或调用付费服务。

Windows本轮在Ubuntu24.04 WSL中用apt-get download取得Redis及运行库，再通过dpkg-deb解压到项目artifacts/redis-runtime，不执行软件包安装脚本或注册系统服务。对应包为Redis7.0.15的Ubuntu安全修订、libjemalloc2、liblzf1、libatomic1；使用可信Ubuntu仓库校验。其他机器可使用其已安装的Redis或上述固定容器镜像，版本结果写入验收JSON。

加 `--browser` 会在API验收后启动独立Redis模式演示实例，运行仓库全部既有前端e2e；`--browser-only`只运行该浏览器范围。首次需在frontend安装依赖和Playwright Chromium，已有完整浏览器可通过 `--chromium` 指定路径。浏览器用例的数据保存在新H2演示目录；MySQL双实例验证与该浏览器范围分别记录。

结果在 `artifacts/c-redis-*/result.json`，失败也保留证据；进程退出时删除临时密码配置并关闭自己的实例，保留合成数据库/AOF/日志供检查。新增CI执行真实MySQL、固定Redis镜像、双JVM登录/故障/恢复，并验证可选Compose配置；配置检查不冒充整套容器应用组网通过。运行结果与保留范围见[本阶段验收](../verification/member-c-redis-2026-11-08.md)。
