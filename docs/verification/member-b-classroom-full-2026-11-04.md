# B整站离线课堂展示验证（2026-11-04）

用户目标：无需SDK或授权，展示时能跑通项目已有业务。将`origin/codex/member-c-completion`的`9ceee3a`合入B分支待整合版本，保留B的自动演示、暂停、幂等和重启恢复，接入C的报表、知识库与助手。未修改已发布迁移；V6来自C。main不自动合并。

## 实际完成

“一键整站演示”依次调用实际组织、排班、二维码、打卡、审批和日记录接口，核对480分钟请假及审批ID；再读取C报表、导出CSV，生成每轮独立合成DOCX，通过C持久化队列和隔离Java解析器提取文本，按员工权限检索，读取并校验带版本与切片位置的原文引用。支持按6—8步骤分别操作；失败即停止，不把上传、解析或问答失败显示成成功。

新增课堂专用原文摘录器，只在`classroom`和`lianzhi.demo=true`同时启用时作为主CitedModel。普通Ollama接口及配置保持原样。摘录仍通过CitedAnswerService实际检索、权限和版本复核、原文引用校验；无依据时拒绝回答。接口状态OFFLINE_EXTRACTIVE和页面均标注“非生成式AI”，并关闭依赖真实模型的申请建议按钮。没有SDK恒真返回、伪造活体结果、预写成功考勤、权限旁路或自动确认申请。

新增前端固定版本依赖fflate 0.8.2，用于浏览器生成真实DOCX；原有Vue、ECharts及锁文件保留。课堂脚本清空外部模型/向量/OCR/转写服务地址，运行时只需Java17+和浏览器。脚本及打包README更新到完整8步。

## 本轮实测

| 验证 | 结果及范围 |
| --- | --- |
| 后端完整`mvnw verify` | 346项通过，0失败、0错误、0跳过；含3项原文摘录边界测试及课堂模式状态/权限断言 |
| 前端format/build | 通过；报表分包仍有Vite体积提示，未隐藏提示 |
| 普通模式完整浏览器 | 46项通过；课堂端点404、业务权限与正常未配置模型状态保持原行为 |
| 实际展示包浏览器 | 9项通过；真实全链路、DOCX隔离解析、CSV内容、原文引用、390px无横向溢出，以及失败/暂停/存储受限/身份不匹配/恢复 |
| 实际停止并换Java进程 | 1项通过；同一演示库恢复2条事件及480分钟请假，业务POST为0 |
| 地图交互仿真 | 4项通过；不等同于真实高德联网或手机GPS实测 |
| 一次性真实MySQL8.4.13 | 考勤/审批45项、数据库重启1项、人脸业务14项通过；人脸提供方明确为TEST_ONLY_SIMULATOR_NOT_REAL_RECOGNITION |
| 迁移 | V1、V2、V3、V4、V4.1、V5、V6；正常空库全量通过，原课堂V5文件库升级V6通过；MySQL重启数据指纹一致 |
| 脚本 | Python构建器编译、sh语法通过；ZIP采用6文件白名单，不包含运行库、日志或上传资料 |

本轮日志：`/tmp/b-full-backend.log`、`/tmp/b-full-build.log`、`/tmp/b-full-normal-e2e-final.log`、`/tmp/b-full-classroom-e2e-final.log`、`/tmp/b-full-classroom-restart-e2e.log`、`/tmp/b-full-map-e2e.log`。真实MySQL证据：`artifacts/mysql-b-0fdb2357e037/result.json`及独立测试日志/XML。截图：`artifacts/classroom-full-success.png`、`artifacts/classroom-full-mobile.png`。运行产物不入Git。

首次浏览器运行因工具回收后台服务而中断，改用持续前台会话；之后发现知识库测试定位漏了标题中的版本号，修正为精确匹配实际标题，未删减业务断言。普通模式一次出现Vite动态模块加载失败，使用全新测试库与稳定依赖缓存重新完整运行后46项通过，保留失败日志供追溯。以上结果以最终完整运行记录为准。

## 交付与边界

构建：先构建后端和前端，停止正在运行的旧app.jar，再执行`python3 scripts/build-classroom-demo.py`。ZIP包含app.jar、Windows/Linux启动脚本、说明和Git版本清单。Windows完整解压后双击start-demo.cmd；选择管理员登录，点击“一键整站演示”。只监听127.0.0.1:18080，使用自己的demo-data。

真实虹软识别/活体、Windows启动器现场执行、真实手机摄像头扫码和GPS、uni-app/小程序、真实大模型生成、向量、OCR和转写不在本轮通过范围。人脸保留设备预览和未配置状态；合成员工、合成制度、固定演示定位均明确标注。本包无需SDK、授权或模型下载，但Windows现场仍需Java17+。主分支合并由A协调。
