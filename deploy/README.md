# Docker Compose部署

需要Redis共享会话时按[共享会话说明](../docs/modules/redis-session.md)叠加 `compose.redis.yaml` 并填写本机Redis密码；默认Compose不自动启动或启用Redis。新增配置支持服务认证、健康检查、独立数据卷和受控故障行为。

Windows 或 Linux 单机 MySQL + 本地真实模型组合启动见 [MySQL与AI服务说明](../docs/modules/mysql-ai.md)，配置样例为 `c-mysql-ai.env.example`。本文件的 Compose 与单机启动分别验收，不共享默认数据目录。

本目录提供Java17后端、Node22前端构建、Nginx同源代理和MySQL8.4配置。与`tools/classroom`离线体验包使用不同配置：此配置显式使用`mysql`，不启用demo/classroom，不生成演示账号。

## 使用

需先安装并启动Docker Engine/Desktop及Compose v2。在仓库根目录执行：

```powershell
Copy-Item deploy/.env.example deploy/.env
# 在本机编辑 deploy/.env，填入三个独立密码，不提交该文件。
docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

构建后访问`http://127.0.0.1:8088`。首次非演示管理员为`admin`，密码取`ADMIN_INITIAL_PASSWORD`；原有账号库不会被初始化覆盖。数据库和头像保存在命名卷，后端、MySQL不映射宿主机端口。后端首次启动需等数据库健康、Flyway迁移和账号初始化完成，网页先出现502时稍后刷新。

查看后端状态：`Invoke-RestMethod http://127.0.0.1:8088/api/system/info`。`/healthz`仅检查Nginx，不代表数据库/AI/SDK全部可用。运行日志：`docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail 100 backend`。停服务使用同样前缀加`stop`；不使用`down -v`清空业务卷。

## 同源与配置边界

- 浏览器只访问web端口，`/api/`保留路径转发到backend，Cookie和CSRF沿用现有认证；SPA路由回退index.html，assets不存在返回404。
- 本地HTTP默认`SESSION_COOKIE_SECURE=false`。实际手机摄像头/定位与互联网部署需可信HTTPS；在TLS入口后设为true并按域名配置证书。此文件没有申请证书或部署公网入口，不能当作HTTPS现场验收。
- 后端绑定容器内部0.0.0.0，外部默认仅127.0.0.1:8088。需要局域网时单独配置WEB_BIND_ADDRESS，配合TLS入口和明确访问范围。
- 头像卷属于非root应用用户，数据库密码不写入镜像。实际备份/恢复由部署方执行，先备份再升级，不修改已发布迁移或开启out-of-order。
- 数据库通过Flyway依次应用V1至V6（含V4.1）。人脸SDK、密钥、阈值均未配置，Compose明确关闭人脸处理。模型/OCR/转写/向量服务同样没有自动启用。
- 高德凭据属于前端构建配置。此模板不复制本机`.env.local`到构建上下文，因此默认地图SDK未配置；已有坐标管理和报表分布图可用，真实高德接入按场所配置文档单独构建和验证。
- 单实例部署：会话及申请确认草稿在内存中，重启使会话失效。申请在MySQL持久化；结果未知时先查询，不能自动重新提交。需要多实例共享会话时使用可选Redis配置；本文件默认运行单实例。

## 相关配置

- [本地模型与媒体服务](../docs/modules/media-services.md)
- [Redis共享会话](../docs/modules/redis-session.md)
- [Nacos与独立向量服务](embedding-discovery.md)
- [备份与恢复](backup.md)
- [Kubernetes](kubernetes/README.md)
