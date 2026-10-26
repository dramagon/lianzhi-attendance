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

推荐 MySQL 8.4，数据库名 `lianzhi`。先以有管理权限的数据库账号执行下面的 SQL，并替换密码占位内容：

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
$env:ADMIN_INITIAL_PASSWORD = '替换为至少12位的初始管理员密码'
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

| 现象 | 检查位置 |
| --- | --- |
| 找不到 java / npm | 安装路径和 PATH，安装后重新打开终端 |
| 前端提示无法连接服务 | 后端是否成功启动、8080 是否被占用 |
| 演示账号登录失败 | 是否误启用了 mysql 模式，是否连接了旧数据库 |
| 登录后刷新跳回登录 | 是否混用了 localhost 和 127.0.0.1；请统一使用 README 地址 |
| MySQL 启动报错 | 数据库是否建立、环境变量、账号权限和 3306 端口 |

2026-10-26已在本机Windows运行后端、前端构建和浏览器测试。机器安装了JDK17，但JAVA_HOME原先指向JDK8；运行项目时需在当前终端设置 `$env:JAVA_HOME='C:\Program Files\Java\jdk-17'`，无需改动其他项目的全局配置。Node24.12.0满足最低版本，团队仍推荐22LTS。MySQL客户端8.0.28可用，但3306未监听；尚未验证真实MySQL连接和迁移。

本机Git访问GitHub使用命令级代理：`git -c http.proxy=http://127.0.0.1:7897 fetch origin`，push同样加该选项。代理仅用于本机，不写入项目共享配置。本轮Wrapper首次下载中央仓库返回403，使用环境变量 `$env:MVNW_REPOURL='https://maven.aliyun.com/repository/public'` 下载同一Maven3.9.11后成功；未更换项目依赖版本。

旧V1库升级后账号/部门保留，company_id为空；由ADMIN明确建立公司、部门并调整员工归属，HR分配公司前无公司业务访问权。不要删除原库来绕过升级。浏览器完整验收建议使用另一个空演示库或独立内存库，避免验收账号进入原有数据。
