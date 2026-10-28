# C模块接口记录

负责人C。范围：统计、报表、导出、知识库与AI助手。当前已实现独立文本处理、PDF/Office 解析、权限检索与版本生命周期组件；HTTP仅有能力状态与明确关闭的业务端点，未应用 V6/V7 迁移。

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
