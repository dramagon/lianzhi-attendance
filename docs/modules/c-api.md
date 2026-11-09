# C模块接口记录

2026-11-08 C2新增[可选Redis共享会话](c-redis-session.md)：默认关闭，保留Session/CSRF/账号版本撤销；开启时跨实例共享登录和确认草稿。会话存储故障返回503 SESSION_STORE_UNAVAILABLE，申请仍须显式确认和MySQL幂等。

2026-11-08新增[正式MySQL与真实AI组合启动及验收](c-mysql-ai.md)：显式非演示数据库模式、跨平台依赖/端口配置与独立真实服务验证；既有业务API和迁移保持不变。

负责人C。范围：统计、报表、导出、知识库与AI助手。当前已实现独立文本处理、解析、权限检索/版本及带来源问答组件；整合A/B后提供真实业务查询、授权汇总及明确确认后的本人申请提交。第六阶段增加筛选报表、趋势、场所出勤热度和CSV导出，详见[当前报表契约](c-reports.md)。当前收尾阶段已正式接入V6持久化、后台解析、真实服务适配及有来源问答，未创建无必要的V7。详见[收尾契约](c-completion.md)与[本机服务启动](c-media-services.md)。下列每日记录为当时状态。

开发时补充真实路径、权限、统计口径、文件格式支持、来源引用、外部配置、迁移和实际验证。任务见 [C任务](../team/member-c.md)，跨模块规则见 [公共约定](../team/contracts.md)。

## 第 1 天：文本处理组件

已实现 `knowledge.text` 的 `SourceLocation`、`TextSection`、`TextNormalizer`、`ChunkingOptions`、`TextChunk` 和 `TextChunker`。这些是供后续格式解析器使用的本地 Java 组件，不含上传、权限过滤、检索、向量化或持久化服务。

具体调用契约、默认限制、Unicode 码点偏移、来源定位、元数据/状态设计与 A/B 依赖见 [知识库设计](c-knowledge-design.md)。验证结果见 [第 1 天记录](../verification/member-c-day1.md)。

## 第 2 天：真实文件解析与提供方契约

已实现 `knowledge.parse.DocumentParser.parse(fileName, bytes)`，支持 PDF、DOCX、XLS/XLSX、PPT/PPTX 的正文解析，并接入现有来源定位和切片组件。空、损坏、加密、伪装、超限及无正文均有明确结果；拒绝 VBA/Excel 4.0 宏表，不执行公式或宏。

具体 Java 契约、来源与内容边界、默认限制、依赖调整及 A/B 接入状态见 [解析组件](c-parsing.md)，验证结果见 [第 2 天记录](../verification/member-c-day2.md)。新增的 OCR、转写、embedding 仅为可替换接口及结果约束，没有真实服务实现。

## 第 3 天：权限检索与接入状态

已实现独立内存工作区的文字匹配、真实向量输入的精确余弦检索、权限过滤、版本更新/删除和来源引用；工作区不是应用Bean，不提供持久化业务入口。新增 Ollama embedding HTTP 适配器及本地协议验证，暂无真实服务配置，不宣称外部模型联通。

登录后 `GET /api/knowledge/status` 返回全部能力关闭和依赖说明；文档/检索/引用写读端点当前返回503，写请求保持CSRF。前端知识库状态页导出模块路由，待A整合根菜单；当前不能通过主站导航进入。契约、精确路径、权限规则、限制与V6激活条件见[检索说明](c-retrieval.md)，实际验证见[第三天记录](../verification/member-c-day3.md)。

旧 DOC、真实上传/数据库保存、OCR、音视频转写、真实 embedding 联通/持久化向量服务、AI 助手、报表/导出目前尚未接入，不把独立组件算作完整知识库或 AI 完成。

## 第 4 天：A/B整合与助手

整合A 182d248（含B e3eafe6）的组织、权限、真实排班、日考勤与审批V4/V4.1；通过A当前认证身份取得C范围，复用B/A服务提供 `/api/assistant/attendance`、`/api/assistant/approvals` 和详情，返回真实业务结果。主站接入知识库关闭态及只读助手页面。

独立RAG流程验证来源ID/原文摘录、模型前后权限与版本；真实Ollama Chat协议适配读取模型/embedding环境配置，无服务不返回假答案。`POST /api/assistant/ask`仍503，V5缺失不越序激活V6。详细接口与边界见[助手说明](c-assistant.md)，验证见[第四天记录](../verification/member-c-day4.md)。前文未接入项为历史状态，今日只读助手已实现，完整AI/持久化知识问答仍未完成。

## 第 5 阶段（2026-11-01）：授权汇总与申请确认

整合A d6ce1b7及B 0b9e99c，保留地图/定位修复、打卡回执和默认关闭的人脸安全流程。C新增管理员/人事授权部门汇总，直接汇总B日结果；新增草稿预览→用户明确确认→A真实申请提交，确认ID绑定当前会话和身份版本，10分钟有效，重复/并发复用A请求编号，不重复申请。

具体 `/api/assistant/attendance/summary`、`approval-drafts`、`approval-drafts/confirm`、取消路径、错误/权限/限额与会话恢复边界见[第五阶段契约](c-day5-actions.md)，验证见[第五阶段记录](../verification/member-c-day5.md)。当前未接模型自动建议，手工草稿不冒充AI生成；V5仍仅为候选，不激活V6。


## 课堂离线整合补充（B，2026-11-04）

`GET /api/assistant/status`的`modelConfiguration`增加`OFFLINE_EXTRACTIVE`：仅`classroom`配置与`lianzhi.demo=true`同时启用时，使用课堂原文摘录器。`/ask`请求和响应结构不变，仍由CitedAnswerService执行实际文字检索、权限、版本和原文引用校验。摘录明确说明“非生成式AI”；没有可读来源返回NO_EVIDENCE，不创建申请或执行审批。普通模式的NOT_CONFIGURED、CONFIGURED_NOT_VERIFIED和Ollama路径保持原行为。

前端在OFFLINE_EXTRACTIVE模式允许有来源问答，关闭依赖真实模型的申请建议按钮；原有手工填写、预览和显式确认流程保留。课堂启动脚本清空外部模型、Embedding、OCR和转写地址，确保离线可重复运行。此模式不代表已通过真实大模型、向量、OCR或转写验收。
可选[向量服务注册与调用](../../deploy/embedding-discovery.md)使用独立工作进程及内部接口 `POST /internal/v1/embeddings`，用户侧知识库 API 沿用现有权限和响应。

2026-11-09整合：模型申请建议已实际接入；无歧义的明确类别及绝对起止时间同时约束生成并在服务端复核，模型替换用户明示值时拒绝且不写申请。严格真实生成→预览→明确确认与独占MySQL/Redis验证范围见[本轮整合记录](../verification/member-a-cloud-integration-2026-11-09.md)；历史阶段“未接入”描述不代表当前状态。Session、CSRF、范围、业务校验与幂等契约保持不变。
