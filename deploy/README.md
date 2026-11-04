# C 同源容器部署

本目录提供Java17后端、Node22前端构建、Nginx同源代理和MySQL8.4配置。与B的`tools/classroom`展示包分开：此配置显式使用`mysql`，不启用demo/classroom，不生成演示账号。

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
- 本整合版正式迁移为V1/V2/V3/V4/V4.1/V5；没有V6。人脸SDK、密钥、阈值均未配置，Compose明确关闭人脸处理。模型/OCR/转写/向量服务同样没有自动启用。
- 高德凭据属于前端构建配置。此模板不复制本机`.env.local`到构建上下文，因此默认地图SDK未配置；已有坐标管理和报表分布图可用，真实高德接入按A文档单独构建和验证。
- 单实例部署：会话及C申请确认草稿在内存中，重启使会话失效。真实A申请在MySQL持久化；结果未知时先查询，不能自动重新提交。没有Redis共享会话、多副本负载均衡或异步报表任务。

## 实际验证状态（2026-11-04）

本机未安装/发现Docker命令，Compose YAML已由PyYAML加载并核对服务/端口结构，但**未执行docker compose config、镜像构建、Nginx启动或容器MySQL验收**。前后端在Windows分别构建和回归，不等同容器验证。B的独立MySQL结果见其11/03记录，不能替代本轮C报表的MySQL联验。

## 课程技术要求记录

| 项目 | 当前证据与后续 |
| --- | --- |
| Docker / 同源Nginx | 已交付配置；本机缺Docker，容器实跑待完成 |
| GitHub Actions | 已有后端/前端/浏览器/地图流水线，不能替代云效验收 |
| 云效 | 尚未配置项目、流水线或凭据 |
| Redis / Spring Task | 未接入；当前同步报表限额读取，不宣称有缓存/调度 |
| Nacos / OpenFeign | 未接入；团队契约规定模块化单体内优先Java服务调用，真实微服务接入须共同协调 |
| Kubernetes | 未部署；不能将Compose当作K8s成果 |
| ECharts | 本轮趋势和坐标热度用原生SVG，无新增共享依赖；ECharts课程学习/验收单独待落实 |
| 预测 / 画像 | 未实现预测模型；当前仅授权历史/实时聚合，不宣称预测 |

详细报表口径与权限见[报表契约](../docs/modules/c-reports.md)。
