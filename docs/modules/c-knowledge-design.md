# C 知识库第 1 天设计

日期：2026-10-26。共同依据：[C 任务](../team/member-c.md)、[公共契约](../team/contracts.md)、[一周计划](../team/member-c-week-plan.md)，及用户提供的实训 PDF 第 6 页多模态解析/切片/向量化要求。

## 当前实现与运行边界

当前交付 `knowledge.text` 包的来源位置、文本规范化和切片组件；没有新增 HTTP 接口、数据库表、前端入口、权限配置或依赖。

调用链：真实格式解析器（后续实现）→ `TextSection` → `TextChunker` 内部规范化 → `TextChunk` → 后续持久化、embedding 与检索。

当前不是 PDF/Office 解析器，不是向量检索，也不接受外部上传。不得把切片测试通过标记为“知识库完成”。V6 必须等 V2～V5 合并后应用；当前没有 Flyway 变更。

## 已实现的 Java 调用契约

| 类型 | 行为 |
| --- | --- |
| `SourceLocation` | FILE/PAGE/SHEET/SLIDE/SECTION/TIME_RANGE；页码和幻灯片从 1 开始；工作表/章节保留名称；音视频范围使用非负毫秒、结束大于开始。拒绝不同类型混填字段。 |
| `TextSection(source, text)` | 解析器输出的原始文本片段。不同页、工作表、幻灯片、时间段保持独立，正文可以为空，source/text 不可为 null。 |
| `TextNormalizer.normalize(raw)` | NFC 规范化，统一换行，合并水平空白和多余空行；保留中文、大小写、全角字符及 emoji；不解释正文里的指令。 |
| `ChunkingOptions` | 切片长度、重叠长度、整篇原始字符预算、来源段数及输出片数上限。代码调用参数，尚未接环境配置。 |
| `new TextChunker(options).chunk(sections)` | 先检查输入总量，再按来源顺序返回不可变切片列表；同样输入与选项产生同样输出；异常不返回半份结果。 |
| `TextChunk` | sequence 为全文 1 起序号；sectionIndex 为输入段落的 1 起序号（空段仍占位置）；source 保留解析器来源；startOffset/endOffset 是该来源段落规范化正文中左闭右开的 Unicode 码点偏移。 |

调用示例只用于开发说明，不代表真实制度或考勤记录：

```java
var sections = List.of(new TextSection(SourceLocation.page(2), "示例制度正文。"));
var chunks = new TextChunker().chunk(sections);
```

### 规范化与切片口径

- 使用 Unicode 码点，不使用 UTF-16 char 或模型 token 计数，不截断代理对；不承诺按完整字素簇切分。
- 使用 NFC，不做 NFKC、不改变大小写、不删除内部格式字符；去除开头空白/BOM，统一 CRLF/CR/Unicode 行分隔符为 LF。水平空白和控制字符按空格处理，避免粘连英文词；最多保留一个空白行。
- 拒绝孤立 UTF-16 代理项，不悄悄插入替换字符。规范化结果再次处理不变。
- 默认切片上限 800 码点、重叠 120 码点。优先在后半段且超过重叠位置的段落末尾结束，其次句末/换行，最后按硬长度切分。
- 同一来源段内连续切片精确重叠、无缺口；不同来源不合并，偏移从各自的 0 开始。空白来源不生成切片；最后一片抵达末尾后停止，不生成只有重叠的尾片。
- 整篇原始正文默认上限 2,000,000 码点，来源段上限 10,000，切片上限 10,000；空白也计入输入预算。预算错误抛出中文 `IllegalArgumentException`，无部分输出。
- 偏移对应规范化文本，不能用于原始文件字节。后续存储必须保留对应版本的规范化段落，引用结合 documentId/version/source/sectionIndex/offsets 回溯。
- 当前不生成向量、数据库 ID 或来源 URL，不读写文件/网络，也没有全局可变状态。

## 文档元数据与状态设计（待实现）

以下是 V6 候选设计，不代表已有表、DTO 或业务服务。

| 字段组 | 候选字段/规则 |
| --- | --- |
| 标识与版本 | documentId、version（从 1 递增）、contentSha256、原版本标识；更新创建新版本，不改写已引用旧版本正文。 |
| 所有权与范围 | companyId、uploadedByUserId、visibility；身份来自后端认证上下文，员工/公司范围复用 A 的服务，不接受客户端自行声明权限。 |
| 文件描述 | originalName（仅展示）、detectedFormat、mediaType、sizeBytes、storageKey、createdAt、updatedAt；服务端生成存储键，文件名不作路径。 |
| 解析 | processingStatus、parserId/version、failureCode、可读提示、sourceSectionCount；不向客户端暴露异常堆栈、文件正文或凭据。 |
| 切片与索引 | normalizerVersion、chunkingOptions/version、chunkCount、embeddingProvider/model、索引版本与 indexingStatus；模型或规则变化触发重建。 |
| 来源段落 | documentId/version、sectionIndex、source、normalizedText；切片关联同一版本来源段落，后续引用查询先鉴权。 |

解析状态建议：RECEIVED → PARSING → PARSED，失败为 FAILED；没有正文为 NO_TEXT，所需服务缺配置为 UNAVAILABLE。上传成功不代表可检索。

索引状态单独管理：NOT_INDEXED → INDEXING → READY，失败为 FAILED、依赖不可用为 UNAVAILABLE。解析成功不等于向量索引成功。更新先生成新索引再切换当前版本；删除/权限变化先阻断召回并失效缓存，再清理数据，避免旧内容继续命中。

删除、更新、重试的幂等与状态竞争控制在真实服务阶段落实和测试，当前没有伪造这些能力。

## 上传与格式支持设计（待实现）

- 当前 multipart 单文件/请求上限均为 10MB，暂沿用，不改共享配置。后续应用层检测实际格式，并限制解压体积/条目数、来源段与提取字符量。
- 空文件、未知格式、损坏/加密且无法解析的文件有明确失败状态；扫描 PDF 无文字时返回 NO_TEXT/需 OCR，不返回伪解析结果。
- 文本型 PDF 保留真实页码；DOCX 未渲染时不能伪造页码，可用章节或段落定位；XLSX 保留工作表及后续行范围；PPTX 保留幻灯片序号；OCR/转写来源来自实际服务。
- PDF、DOC/DOCX、XLS/XLSX、PPT/PPTX 分别登记实际支持版本，不能用新格式成功代表旧格式也支持。当前以上格式均未接入。
- 图片 OCR、音视频转写、embedding、对话模型分别配置和验收，本地文本处理不证明外部服务可用。
- MIME/扩展名仅作初筛；文档正文是不可信数据，不能改变检索范围、系统提示或工具权限。

## 依赖清单与下一步

| 依赖 | 当前事实与要求 |
| --- | --- |
| A 组织/范围 | main 尚无新业务服务。需要员工/公司/部门/场所 DTO、调用者范围校验、文档范围映射约定；文档权限由 C 执行。未就绪不开放持久化入口。 |
| A 共享基础 | 等待共享业务写请求 CSRF、输入校验和异常；不改现有认证，不为绕过 403 放宽 API。 |
| B 考勤 | main 尚无事件/日记录服务。报表/AI 需要统一口径、最终结算标识、范围过滤后的查询和可回查来源。 |
| 模型/向量/OCR/转写 | 仓库尚无配置与适配器；本次只检查下列候选环境变量存在性，未发现配置，未发出外部服务请求；不能推断用户其他软件/账号没有服务。 |
| 格式解析库 | 当前 POM 无 PDF/Office 解析库。后续逐格式评估并在 PR 中列出公共依赖变更，不擅自替换技术栈。 |

候选配置名（应用当前尚不消费，未提交任何值）：

- `LIANZHI_AI_BASE_URL`、`LIANZHI_AI_API_KEY`、`LIANZHI_AI_MODEL`
- `LIANZHI_EMBEDDING_BASE_URL`、`LIANZHI_EMBEDDING_API_KEY`、`LIANZHI_EMBEDDING_MODEL`
- `LIANZHI_OCR_BASE_URL`、`LIANZHI_OCR_API_KEY`
- `LIANZHI_TRANSCRIPTION_BASE_URL`、`LIANZHI_TRANSCRIPTION_API_KEY`

下一步接真实 PDF/Office 解析器与合成样例，复用本次组件；随后接权限检索、V6、外部模型和业务查询，分别记录能力完成情况。

## 同步节奏

用户已调整为：当天开发过程中只本地保存和验证，当天任务全部结束后统一 commit/push 并更新 PR。以用户最新要求为准，不按每个组件频繁提交；报告远端 SHA、检查结果与未完成项。
