# Windows 环境与启动

## 需要安装

1. JDK 17，设置 `JAVA_HOME` 并将 Java 加入 PATH。
2. Node.js 22 LTS（项目要求至少 20.19，推荐统一使用 22）。
3. Git，用于拉取、提交与同步代码。
4. IDEA 用于编辑后端；前端可以使用 VS Code。

安装后重新打开 PowerShell，运行 `java -version`、`node -v`、`npm.cmd -v`、`git --version`，确认命令均可用。

## 演示模式

首次拉取后，在项目根目录打开两个终端，分别运行 README 中的启动脚本。后端日志出现 `Started AttendanceApplication` 后，访问前端地址。

脚本内使用 `npm.cmd`，避免 PowerShell 的脚本策略阻止 `npm.ps1`。命令中的 `-ExecutionPolicy Bypass` 仅作用于当次 PowerShell 进程。

默认后端端口为 8080，前端为 5173。如果端口被占用，先关闭原服务。前端设置了端口严格检查，避免自动跳号导致地址混乱。

在 IDEA 中直接运行也可以：打开 `backend/pom.xml`，选择 JDK 17，导入 Maven 项目，然后运行 `AttendanceApplication`。启动工作目录设为 `backend`，使演示数据库位置与脚本一致。

演示数据库文件位于 `backend/data/`。如需重置，先停止后端，再备份或删除这个目录；下次启动会重新建表、生成演示账号。不要在有需要保留的数据时执行重置。

## MySQL 模式

项目支持MySQL8；本机已使用MySQL8.0.28实际验证，数据库名 `lianzhi`。下面提供本机脚本与手动配置两种方式。

### 本机脚本（已验证Windows PowerShell 5.1）

在项目下创建 `artifacts/mysql-credentials.local.ps1`，仅填写本机MySQL管理账号，不提交Git：

```powershell
$env:LIANZHI_MYSQL_ADMIN_USER = 'root'
$env:LIANZHI_MYSQL_ADMIN_PASSWORD = '填写已有管理账号密码'
```

先只读检查，再初始化并启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-mysql.ps1 -CheckOnly
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-mysql.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-mysql-backend.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

`setup-mysql.ps1`默认使用 `D:\MySQL\MySQL Server 8.0\bin\mysql.exe`，其他机器通过 `-MysqlPath` 指定。管理凭据也可通过当前进程环境变量提供，或使用 `-CredentialFile` 指定本机文件。脚本检查已有库：有表但不符合项目结构时停止；不删除表、不重设已有账号密码。新建账号仅授权 `lianzhi.*`，不授予全局权限；已有专用账号无本机配置时停止，需提供原密码。脚本复跑保留已有密码与数据。

应用数据库随机密码、`admin`初始密码和JDBC地址保存在 `artifacts/mysql-app.local.ps1`，不打印密码。`start-mysql-backend.ps1`显式启用mysql配置并拒绝错误JDK版本、清除残留H2/验证库数据源覆盖。非演示模式不会创建hr/employee，首次登录使用admin及该本机文件中的ADMIN_INITIAL_PASSWORD。已有管理员不会被重新生成或重设密码。

独立MySQL验证库（含明确的测试演示资料，不作为正式业务库）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

测试脚本复用只读管理预检，建立 `lianzhi_verify` 及仅有该库权限的同名账号；密码保存在 `artifacts/mysql-verification.local.ps1`。组织/认证/头像/审批API及ApprovalWorkflowTest使用真实MySQL，后者无外层测试事务、使用独立连接验证提交/回滚/并发；纯策略无数据库，显式隔离迁移/并发/B工作流夹具仍为H2，分类见本轮验证记录。正式 `lianzhi` 库不运行这些夹具，不自动清理已有数据。不要将根账号配置、随机密码文件或数据库文件提交仓库。

A2.2已接入B正式V3与调整服务，启动按V1→V2→V3→V4→V4.1迁移。V4是申请与审计，V4.1修复原始打卡时间的微秒精度；不占用B的人脸V5。升级正式库前先使用mysqldump备份，不手工改已发布V1～V3、不启用out-of-order、不删原始事件。旧的docs候选V4已移除。全部审批写入与B使用同数据源READ_COMMITTED事务；已生效申请暂不支持撤销。

### 手动配置

以有管理权限的数据库账号执行下面的 SQL，并替换密码占位内容：

```sql
CREATE DATABASE lianzhi CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'lianzhi'@'localhost' IDENTIFIED BY '替换为你自己的数据库密码';
GRANT ALL PRIVILEGES ON lianzhi.* TO 'lianzhi'@'localhost';
```

然后在后端所在的 PowerShell 窗口设置：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:DB_USERNAME = 'lianzhi'
$env:DB_PASSWORD = '替换为你自己的数据库密码'
$env:ADMIN_INITIAL_PASSWORD = '替换为至少4位的初始管理员密码'
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

首次启动会自动执行 Flyway 迁移，并创建 `admin` 账号。后续数据库已有账号时，不会再次创建或覆盖账号，修改环境变量也不会重置已有密码。MySQL 模式不创建演示账号；ADMIN登录后通过“组织与场所”建立公司、部门、场所，再创建员工和分配HR。

默认连接本机 3306。如需其他数据库地址，使用 `DB_URL` 环境变量。数据库密码、初始管理员密码放在本机环境变量中，切勿写入仓库。

## 访问与安全

- 默认绑定 `127.0.0.1`，只供本机访问。
- 本地 HTTP 开发时 `SESSION_COOKIE_SECURE=false`，部署到 HTTPS 时设为 `true`。
- 员工手机布局已经适配，但手机局域网联调和摄像头权限将在对应模块完善。
- 当前不需要 Nacos、Redis、Docker 或 Kubernetes 即可体验第一阶段。
- 后端仅提供 API；打开 `http://127.0.0.1:8080` 不会显示前端页面，请访问 5173。
- 如果依赖下载受网络限制，可配置自己的 Maven / npm 镜像。环境代理设置属于本机配置，不提交仓库。

## 常见排查

### 高德地图凭据

1. 在[高德开放平台](https://lbs.amap.com/)注册/登录，按页面要求完成开发者认证。
2. 控制台进入“应用管理→我的应用”，创建应用，例如“联智考勤”。
3. 添加Key，服务平台选择 **Web端(JS API)**；创建后取得Key及配套安全密钥securityJsCode。参见[官方准备说明](https://lbs.amap.com/api/javascript-api-v2/prerequisites)。
4. 复制 `frontend/.env.example` 为本机 `frontend/.env.local`，填写 `VITE_AMAP_KEY` 和 `VITE_AMAP_SECURITY_CODE`；已有本机文件时直接编辑，勿覆盖其他配置。
5. 重启前端，登录管理员/人事，进入“组织与场所→办公场所”，打开新增或编辑弹窗。地图点击后先预览，点击“采用地图位置”才填入表单，最终保存才落库。

需要同时配置Key和安全密钥，依据[高德安全密钥说明](https://lbs.amap.com/api/javascript-api-v2/guide/abc/jscode)。本机文件不提交Git或发送到聊天。若出现INVALID_USER_DOMAIN，检查域名白名单与当前访问地址；官方[技术咨询](https://lbs.amap.com/cooperation/technical_advisory)说明本地调试可暂不设置白名单，部署时按实际域名配置。

高德选点为GCJ02，仅可作为该坐标系档案保存；当前B打卡只支持WGS84，不能直接拿地图坐标当WGS84使用。设备定位采集WGS84，仍需核对真实办公位置。

地图仿真验收先启动隔离演示后端，再在frontend运行 `npm run test:map`。它在5174启动独立前端，以合成凭据及模拟SDK验证交互，不修改本机配置，不访问真实高德服务。真实地图加载、瓦片、授权和手机定位须另外实测。

| 现象 | 检查位置 |
| --- | --- |
| 找不到 java / npm | 安装路径和 PATH，安装后重新打开终端 |
| 前端提示无法连接服务 | 后端是否成功启动、8080 是否被占用 |
| 演示账号登录失败 | 是否误启用了 mysql 模式，是否连接了旧数据库 |
| 登录后刷新跳回登录 | 是否混用了 localhost 和 127.0.0.1；请统一使用 README 地址 |
| MySQL 启动报错 | 数据库是否建立、环境变量、账号权限和 3306 端口 |

2026-10-26已在本机Windows运行后端、前端构建和浏览器测试。机器安装了JDK17，但JAVA_HOME原先指向JDK8；运行项目时需显式选择JDK17，无需改动其他项目的全局配置。Node24.12.0满足最低版本，团队仍推荐22LTS。MySQL80原本已运行，3306实际可连接；前次受限检查误判为未监听，已用原生客户端和系统端口检查纠正。本轮使用用户提供的本机凭据完成MySQL8.0.28的V1/V2迁移、非演示登录及独立库集成验证，未重启MySQL服务或更改root密码。

本机Git访问GitHub使用命令级代理：`git -c http.proxy=http://127.0.0.1:7897 fetch origin`，push同样加该选项。代理仅用于本机，不写入项目共享配置。本轮Wrapper首次下载中央仓库返回403，使用环境变量 `$env:MVNW_REPOURL='https://maven.aliyun.com/repository/public'` 下载同一Maven3.9.11后成功；未更换项目依赖版本。

旧V1库升级后账号/部门保留，company_id为空；由ADMIN明确建立公司、部门并调整员工归属，HR分配公司前无公司业务访问权。不要删除原库来绕过升级。浏览器完整验收建议使用另一个空演示库或独立内存库，避免验收账号进入原有数据。

2026-10-31 三人整合版本仍使用上述启动方式。登录后“考勤工作台”“我的申请”和“业务助手”的本人记录查询可用；知识库上传/检索及模型提问保持关闭，不需要配置模型来运行现有业务。整合不新增数据库迁移，当前正式版本为 V4.1。完整验收使用独立 `lianzhi_verify` 演示实例，结束后须切回正式 MySQL 配置；不要在正式库开启演示模式。实际结果见[本轮整合验收](verification/member-a-integration-2026-10-31.md)。
