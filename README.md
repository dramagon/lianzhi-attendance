# 联智 · 企业智能考勤与业务助手

面向企业日常人员管理、考勤与制度查询的应用。将组织档案、排班打卡、申请审批、统计报表和知识库集中在同一平台，支持管理员、人事和员工按权限使用。

## 功能

| 功能 | 说明 |
| --- | --- |
| 账号与组织 | 公司、部门、员工档案，账号分配、角色管理、停用、个人头像与密码修改 |
| 办公场所 | 地图选点、坐标管理、员工绑定与打卡范围设置 |
| 排班与打卡 | 班次管理、二维码与定位打卡、个人考勤月历、原始记录与CSV导出 |
| 人脸验证 | 本人授权登记、虹软服务端检测与比对、RGB活体检测，验证结果绑定打卡请求 |
| 申请与审批 | 请假、补卡、加班，申请撤回、审批历史与考勤回写 |
| 数据报表 | 按公司、部门、员工、场所和日期查询工时、考勤趋势与明细 |
| 企业知识库 | 文档上传、解析、版本管理、文字及向量检索，支持图片OCR与语音转写 |
| AI业务助手 | 根据授权文档回答问题并显示出处，查询考勤和申请，生成申请草稿并由用户确认提交 |
| 移动端 | 响应式网页及独立uni-app客户端，支持H5与微信小程序构建 |

人脸、地图、模型、OCR和转写需配置对应服务或凭据；未配置时页面会提示。AI申请必须由用户核对并明确确认，业务数据与来源均受服务端权限校验。

## 技术栈

后端使用Java 17、Spring Boot、Spring Security、MyBatis和Flyway；前端使用Vue 3、Vite、Element Plus、Pinia及ECharts。业务数据库支持MySQL，本地体验可使用H2。支持Docker Compose部署，以及可选的Redis共享会话、Nacos服务发现和OpenFeign向量服务调用。

## 快速运行

安装JDK 17与Node.js 22，在项目根目录分别打开两个PowerShell窗口：

```powershell
# 后端，默认端口8080
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

```powershell
# 前端，默认端口5173
powershell -ExecutionPolicy Bypass -File .\scripts\start-frontend.ps1
```

打开 [本机页面](http://127.0.0.1:5173)。首次运行需联网下载依赖，默认本地体验模式使用H2文件库，重启保留数据。

| 本地体验身份 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `Lianzhi@2026` |
| 人事 | `hr` | `Lianzhi@2026` |
| 员工 | `employee` | `Lianzhi@2026` |

以上账号只在演示模式生成。MySQL模式首次管理员密码通过环境变量配置，不使用上述默认密码。

已经安装本地模型、媒体服务和SDK的电脑，可运行根目录 `start-ai.cmd` 启动完整应用，默认访问 [18080端口](http://127.0.0.1:18080)。该入口依赖本机配置，不会随仓库分发模型、SDK或授权。

macOS/Linux可分别在 `backend` 运行 `./mvnw spring-boot:run`，在 `frontend` 运行 `npm ci`、`npm run dev`。

## 使用流程

1. 管理员建立组织、分配账号，配置办公场所并安排班次。
2. 员工扫描场所二维码、获取定位后打卡；启用人脸时先登记并完成验证。
3. 员工提交请假、补卡或加班申请，人事审批后查看考勤结果。
4. 管理人员查看统计报表；员工上传有权使用的资料，通过业务助手查询制度及本人业务。

## 配置与部署

- [Windows与MySQL](docs/windows-setup.md)
- [Docker Compose](deploy/README.md)
- [本地模型、OCR与语音服务](docs/modules/media-services.md)
- [MySQL与AI组合启动](docs/modules/mysql-ai.md)
- [人脸SDK配置](docs/modules/face-setup.md)
- [Redis共享会话](docs/modules/redis-session.md) · [向量服务发现](deploy/embedding-discovery.md)
- [移动端](mobile/README.md) · [移动设备检查](docs/mobile-checklist.md)
- [备份与恢复](deploy/backup.md) · [Kubernetes配置](deploy/kubernetes/README.md)

数据库、头像、人脸特征和加密密钥需按部署环境备份。凭据和本机数据不提交仓库。手机摄像头与定位使用可信HTTPS，Windows人脸SDK须在对应系统与授权下运行。

## 项目结构

```text
backend/     后端业务、权限、数据库迁移与测试
frontend/    管理端与员工网页
mobile/      uni-app移动客户端
scripts/     启动、构建及检查工具
tools/       独立运行与设备工具
deploy/      容器、服务配置与备份工具
docs/        使用、接口及配置说明
```

## 开发检查

后端：在 `backend` 运行 `mvnw.cmd test`（macOS/Linux为 `./mvnw test`）。前端：在 `frontend` 运行 `npm run format`、`npm run build`；浏览器测试需先启动本地演示后端，再运行 `npm run test:e2e`。移动端命令见其运行说明。

接口与权限参见 [API文档](docs/api.md)。
