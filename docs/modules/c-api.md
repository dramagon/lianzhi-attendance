# C模块接口记录

负责人C。范围：统计、报表、导出、知识库与AI助手。当前已开始独立文本处理组件开发，尚无可调用的新业务 HTTP 接口，也未应用 V6/V7 迁移。

开发时补充真实路径、权限、统计口径、文件格式支持、来源引用、外部配置、迁移和实际验证。任务见 [C任务](../team/member-c.md)，跨模块规则见 [公共约定](../team/contracts.md)。

## 第 1 天：文本处理组件

已实现 `knowledge.text` 的 `SourceLocation`、`TextSection`、`TextNormalizer`、`ChunkingOptions`、`TextChunk` 和 `TextChunker`。这些是供后续格式解析器使用的本地 Java 组件，不含上传、权限过滤、检索、向量化或持久化服务。

具体调用契约、默认限制、Unicode 码点偏移、来源定位、元数据/状态设计与 A/B 依赖见 [知识库设计](c-knowledge-design.md)。验证结果见 [第 1 天记录](../verification/member-c-day1.md)。

PDF/Word/Excel/PPT、OCR、音视频转写、embedding/向量检索、AI 助手、报表/导出目前均尚未接入，不把文本切片算作格式解析或知识库完成。
