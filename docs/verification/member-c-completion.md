# C收尾验收（2026-11-04，Windows）

本轮基于C第六阶段 `2ec18ad`，分支 `codex/member-c-completion`。通过GitHub插件重新核对：main `67969ac`、A地图 `d6ce1b7`、B `5c21217` 无新增前置，已整合代码没有缺少新的A/B提交。保留原PR链，A负责最终合并。

## 实际通过

| 检查 | 证据与范围 |
| --- | --- |
| 后端全量 | 最终同源打包 `artifacts/c-package-1398933bc134/backend-verify.log`：343项，无失败、错误或跳过，Java21编译release17 |
| 浏览器回归 | 38项全部通过，`artifacts/completion-final-browser.log`；使用本机已安装Chromium1194完整浏览器，原测试断言保留 |
| 地图回归 | 原4项SDK仿真全部通过，`artifacts/completion-final-map.log`；不算真实地图密钥联通 |
| 前端 | 格式化、主站生产构建和C独立构建通过；生产依赖audit零漏洞；ECharts路由包约600KB有体积提示，未隐藏警告 |
| 真实MySQL | MySQL8.0.41独立临时实例，31项C持久化/提供方协议/报表测试和1项实际数据库重启验证通过；迁移1/2/3/4/4.1/5/6。提供方测试为受控HTTP协议替身，与下述真实推理分开 |
| 大向量与失效 | 验证4096维JSON超过MySQL TEXT上限仍由LONGTEXT保存；模型配置指纹变化显示STALE，文字仍可读、向量需重建；50版文档仍可删除清除原件 |
| 打包运行 | 独立Java同源应用实际启动；包内真正的PropertiesLauncher解析子进程成功完成DOCX上传、索引、搜索、引用和下载；重启后原文/指纹保持；删除后引用404 |
| 页面生命周期 | 桌面与390px手机：上传、更新、来源核对、历史、旧版本404、删除后失效；修复员工公司信息读取和离页轮询清理 |
| 真实本地embedding | Ollama `qwen3-embedding:0.6b` 实际生成1024维向量，Java入库并VECTOR检索成功 |
| 真实本地问答 | Ollama `qwen2.5:1.5b` 对合成年假制度返回ANSWERED和逐字原文引用，通过服务端当前来源校验；失败初测留在忽略目录日志，未冒充通过 |
| 真实OCR | RapidOCR CPU ONNX对合成PNG和扫描PDF识别“年假规定／每年五天”，经Java后台任务形成PAGE引用并向量索引 |
| 真实转写 | faster-whisper tiny CPU int8识别Windows合成普通中文语音，FFprobe真实6.9122秒，来源TIME_RANGE为0–2500与2500–6500毫秒；转写标点和误字有局限，需核对音频 |
| 真实模型建议 | 完整时间与原因的请假指令返回ready=true，A内容预校验通过；前后申请数不变，模型建议不创建申请；页面核对、预览并明确勾选后才创建一份真实申请 |
| 一键启动 | `start-c-full.cmd`调用独立启动器，只绑定回环地址，检查端口，启动自己的Ollama/媒体/Java进程，随机令牌不显示；停止/重开保留独立演示数据 |

真实服务综合证据在忽略目录 `artifacts/local-ai/real-c-integrated-verification.json`、`artifacts/completion-real-integration.log`。仅使用合成制度、合成图和系统TTS音频，无真实员工、人脸、业务文档或付费密钥。模型运行时来自官方Ollama，下载模型由Ollama校验；CPU选择性下载检查HTTP区间和ZIP CRC，不声称验证了完整1.6GB包的SHA。

首次直接运行默认浏览器配置因本机缺少相应headless-shell而未启动测试；改用同版本已安装完整Chromium后38项通过，没有删除/跳过用例。小模型最初未给出完整结构，保持拒答，随后改用JSON Schema并调整清晰的任务提示，仍保留后端引用、权限和业务校验。原测试仅调整“format=json”为结构约束对象、以及关闭态变为实际V6连通态的断言；关闭配置回归继续保留。

## 发布时仍待远端结果

本机没有Docker。新增 `C容器集成验证` 工作流使用真实Docker Compose构建MySQL8.4/Nginx/后端，检查登录CSRF、V6、容器内真实解析、报表CSV、重启保留和删除失效，并只清理随机测试项目。本记录在统一提交前写入，不能预先把未运行的远端工作流写为通过；发布后的具体结果在PR和交付回复中提供。现有完整基础工作流也须检查。

## 云端结果与显示修正

统一提交 `29e73d5` 后，[基础工作流](https://github.com/dramagon/lianzhi-attendance/actions/runs/41811437033)与[真实Docker容器工作流](https://github.com/dramagon/lianzhi-attendance/actions/runs/41811437094)首次运行均通过。后者实际构建后端/前端镜像和MySQL8.4，完成同源Session/CSRF、V6、容器内解析、检索/引用/下载、报表CSV、重启保留及删除失效，未配置模型返回503。容器验收现已具备云端证据，Kubernetes仍未部署。

最后目检发现全站SVG图标样式把ECharts压成20×20，已用组件内局部尺寸覆盖修复，并将图例明确放在顶部，避免与手机缩放条重叠。实际手机SVG恢复320×280、桌面随容器宽度变化，数据标注可见且无横向溢出；保留修正前后截图在忽略目录。该显示修正作为收尾补丁提交，最终远端检查另见PR。

## 仍需整组/外部环境

- 真实AMap凭据及域名限制没有配置，连续热力实现与仿真不等于真服务验收。
- B真实人脸SDK、手机摄像头/定位权限、A/B完整现场业务链需其负责人共同验收；C保留未配置状态。
- Kubernetes只交付声明、Secret/PVC说明，未在集群部署。Redis、Nacos、OpenFeign、uni-app和云效未在本仓库接入，依课程及共同分工仍需落实，不列作C已完成成果。
- 自动识别及小型模型的真实样例通过不代表所有资料准确。旧DOC不支持；扫描PDF20页、音视频120秒及其他限额见契约。预测仅透明历史基线，不是个人画像或成熟预测系统。

当前代码、报告及演示步骤见[收尾契约](../modules/c-completion.md)、[真实服务启动](../modules/c-media-services.md)、[报告章节](../modules/c-report-chapter.md)。基础包不含模型/原库/密钥，完整本机服务保存在忽略目录，不随GitHub自动分发。
