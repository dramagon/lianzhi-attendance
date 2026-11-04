# C：权限检索、文档版本与知识库接入边界

日期：2026-10-28。承接[解析组件](c-parsing.md)、[第三天设计](c-day3-design.md)、[团队契约](../team/contracts.md)。

本文件保留第三天状态。2026-10-29已整合A/B、接入真实身份桥接和主站路由，并新增只读助手；当前接口与仍未开放的知识持久化边界见[第四天助手说明](c-assistant.md)。

## 已实现与未开放

已实现独立 Java 内存工作区 `knowledge.retrieval.KnowledgeWorkspace`，串联真实解析、规范化、切片、权限检索、版本更新、删除及来源引用。它没有 Spring 注解，应用不创建实例，也没有 HTTP 上传接入；它是集成前的有限额可执行逻辑，数据不持久化，不替代 V6、事务、真实员工范围服务或生产知识库。

应用只提供登录后的能力状态及明确关闭的业务端点。V2～V5 尚未顺序合入 main，禁止提前激活 V6。当前没有 MySQL 知识库表、向量数据库、持久化索引/任务、原文件保存、重试队列或隔离解析进程。真实 embedding 未配置，未做真实外部服务验收。不能将下面的合成样例测试表述为完整上传业务已完成。

## Java 契约

| 调用 | 行为 |
| --- | --- |
| `KnowledgeWorkspace(parser, chunker, provider, limits)` | 依赖显式注入，不自动连接外部服务或数据库。每个工作区固定一个提供方实例/模型配置；变更模型须重建所有索引。 |
| `upload(actor, companyId, visibility, name, bytes)` | 校验范围、展示文件名及输入预算；生成 UUID、版本1、SHA-256；解析并切片；可配置向量提供方。原始字节只在本次调用中持有，工作区不保存原文件。 |
| `replace(actor, id, expectedVersion, visibility, name, bytes)` | 校验管理权限和版本，保持原公司/所有者；版本递增后立即失效旧文本/索引。有效新版本发布失败时，不恢复旧内容。 |
| `delete(actor, id, expectedVersion)` | 清除正文/向量，以递增版本的墓碑阻止在途任务重新发布。之后获取/引用/再次删除均 NOT_FOUND，不再可检索。 |
| `get/list(actor, …)` | 鉴权后返回元数据，列表先过滤后分页；page=1～1000000、size=1～100，返回 items/total/page/size。墓碑不显示，处理失败文档仍显示其状态。 |
| `search(actor, query, mode, topK, minimumScore)` | query规范化后1～500码点，原始UTF-16长度≤2000；topK=1～50，阈值[-1,1]且有限；先过滤权限、当前版本及可检索状态，再排序/截取。返回不可变结果。 |
| `cite(actor, id, version, sequence)` | 重新鉴权，只返回当前 PARSED 版本的实际切片；版本失效、已删除、未完成、超出序号、无权均 NOT_FOUND。 |

Document 包含 `id/version/name/access/sha256/processing/indexing/message/chunkCount`。引用包含 `documentId/version/chunk`，chunk 沿用 sectionIndex/source/Unicode码点偏移和正文。偏移对应规范化后的该来源正文，不能用于原文件字节。引用是数据，不是指令；正文不能改写身份、访问范围或未来 AI 工具规则。

内部 `KnowledgeFailure` 的 code：INVALID_INPUT / FORBIDDEN / NOT_FOUND / CONFLICT / LIMIT_EXCEEDED / UNAVAILABLE。无权读取和不存在统一为 NOT_FOUND，避免探测文档存在性。它尚未映射为可用业务 HTTP 错误。

## 权限策略及 A 接入要求

`KnowledgeActor(userId, role, companyId)` 是可信的内部调用参数，不是 HTTP DTO。当前没有认证桥接层，不能从请求 JSON、查询参数或模型输出构造它。后续桥接必须每次使用认证上下文、A 的 `EmployeeService.get` 与 `DataScope` 核验当前启用身份、公司和管理职责；不能只信任旧会话、前端选中的公司或缓存的 Actor。

| 角色 | 读取 | 维护 |
| --- | --- | --- |
| ADMIN | 全局文档 | 全局 |
| HR | 当前所属公司所有文档，包括公司内私有资料 | 本公司 |
| EMPLOYEE | 同公司 COMPANY 文档、同公司且本人所有的 PRIVATE 文档 | 仅同公司本人 PRIVATE 文档 |

PRIVATE 表示不向普通同事开放，不是对公司管理人员加密。上传页接通时必须明确此策略。员工不能自行发布/变更为公司制度。非管理员未分配公司时不能访问公司资料；同一个 userId 转移公司后也不能继续读取旧公司的私人文档。文档不允许跨公司原地迁移，需重新上传并重新授权。

此策略已独立验证，仍需 A 整合时确认并接入真实范围服务；不宣称已完成真实数据库公司的权限联调。

## 状态、并发与限额

- processing：PROCESSING → PARSED / NO_TEXT / FAILED；删除为 DELETED。只有 PARSED 可进行文字检索。
- indexing：NOT_INDEXED / READY / UNAVAILABLE / FAILED。提供方未配置、请求失败或输出不合约时，保留解析正文、显示 UNAVAILABLE，不生成部分索引；文本匹配可以独立测试。资源/切片失败会显示明确处理失败。
- 更新/删除通过 expectedVersion 拒绝陈旧操作。解析及向量网络请求在锁外运行，发布时检查仍为同一版本的 PROCESSING；后续替换或删除后旧任务返回 CONFLICT。没有后台任务或自动重试。
- 查询的向量网络请求结束后，才在锁内扫描当前有效版本和访问范围；不会使用请求开始时缓存的旧候选。没有查询结果缓存。
- 默认工作区上限：100个文档槽位（含墓碑）、20000切片、4000000来源正文码点、1000000向量数值；单文件≤10MiB，解析/切片仍受第二天各自预算限制。合计预算在发送文档前及发布前检查；并发竞争导致超限时失败，不保留部分内容。
- 输入字节复制后处理；公开列表/引用不可变。墓碑会占用容量，此实现没有生产清理策略。限额不保证任意恶意文档的 CPU/堆内存硬上限；正式上传前仍须隔离工作进程、执行超时和并发限制。

## 两种检索模式

TEXT 是 NFC/空白规范化后的、不区分大小写的连续文字包含匹配，命中得分固定1，按文档UUID/切片序号稳定排序；不支持分词、模糊搜索或语义理解，不称作向量检索。

VECTOR 只消费提供方实际输出的 embedding，以精确余弦相似度排序（先缩放再单位化，避免极大/极小有限值溢出）；没有 embedding 时明确 UNAVAILABLE。单向量最多4096维、有限且非零；数量和各批次维度必须一致。查询/文档维度变化时要求重建索引，不返回误导分数。阈值不承诺代表答案可信度。

向量保存在当前内存工作区，属于小规模精确扫描，不是持久化向量服务或 ANN。单元测试使用明确标注的合成向量验证数学与隔离；生产代码没有假向量提供方。

## 真实 embedding 适配

`OllamaEmbeddingProvider(baseUrl, model[, timeout])` 实现现有 EmbeddingProvider，通过服务器配置的根地址 POST `/api/embed`，请求 `model/input/truncate=false`，正文输入按顺序批量发送。协议依据 [Ollama 官方文档](https://docs.ollama.com/api/embed)。应用没有自动注册此提供方或读取下列环境变量；后续服务装配须由服务器读取并传入构造函数。

```text
LIANZHI_EMBEDDING_BASE_URL=http://127.0.0.1:11434
LIANZHI_EMBEDDING_MODEL=<已在服务端安装的准确模型名/标签>
```

示例地址不代表本机安装了 Ollama；代码不安装或下载任何模型。仅支持 Ollama 协议，不承诺兼容其他服务。不需要密钥的本地服务为本次适配目标；未实现带令牌的远端服务适配，不将密钥放进 URL。

baseUrl 只允许HTTP(S)根地址，不含用户凭据/路径/查询/片段；由部署者提供，不能来自用户请求。模型名使用服务实际返回的准确名称/标签，响应模型必须与配置一致。AVAILABLE 仅表示配置齐全，不是连接/模型健康检查；文档/问题会发送至该配置服务，正式启用前由部署者核对数据去向。

默认整体超时20秒，可配置1毫秒～60秒；连接、响应头及慢响应体受限。每批1～32条，每条≤16000 UTF-16单元、总计≤64000；响应最多8MiB，接收过程中限制，含无Content-Length响应。禁止重定向和自动重试；校验HTTP状态、JSON、模型、数量、维度、有限非零数值。不向用户暴露服务端响应正文、URL或异常。失败没有向量输出，不回退到假向量。

## 当前 HTTP 与页面

沿用 ApiResponse、Session、HttpOnly Cookie、CSRF；未修改 SecurityConfig、根路由、菜单或共享 api.js。

| 方法与路径 | 当前行为 |
| --- | --- |
| GET `/api/knowledge/status` | 登录后200；available/uploadAvailable/textSearchAvailable/vectorSearchAvailable均false，phase=DEPENDENCIES_PENDING，blockers为中文依赖说明。不是健康检查。 |
| GET `/api/knowledge/documents` | 登录后503，data=null，不返回伪空列表。 |
| POST `/api/knowledge/documents` | 登录与CSRF后503，不解析/保存文件、不返回文档ID。 |
| GET/PUT/DELETE `/api/knowledge/documents/{id}` | 同样503；写请求仍需CSRF。 |
| GET `/api/knowledge/documents/{id}/sources/{sequence}` | 同样503，没有原文下载或引用开放。 |
| GET/POST `/api/knowledge/search` | 同样503；POST需CSRF。 |

关闭响应为 `KNOWLEDGE_UNAVAILABLE`、中文说明、data=null；匿名读取401，无CSRF写入403。控制器暂不绑定公司/userId/角色/文档正文等业务输入，也不暴露内存工作区。

前端 `modules/intelligence/routes.js` 默认导出 knowledge 子路由，挂在 A 的已认证布局；状态页所有登录角色均可查看。当前根路由尚未引入，直接访问 `/knowledge` 仍为旧站404。页面显示关闭状态、加载、错误与重新加载，上传/检索禁用，不显示假文档/假数量。未来真正启用操作必须另行接入后端授权，不以状态标记代替权限。

主站暂未导入模块，因此额外 `node frontend/scripts/build-intelligence.mjs` 编译懒加载页面；CI已在原构建之后加入此检查。产物写忽略目录 frontend/artifacts，不替换主站dist。公共变更仅此CI检查及构建脚本，供 A 审阅；没有新增npm/Maven依赖。

## V6 与生产激活清单（待实施）

1. A 确认以上文档范围，V2→V3→V4→V5合并；使用新的独立验证库验证Flyway，不启用out-of-order。
2. V6保存文档当前版本/删除标记、不可变版本元数据、规范化来源、切片、提供方/模型/维度/索引版本及任务状态。表关联真实org_company/sys_user；唯一约束(document_id,version,sequence)，引用绑定同一版本。
3. 用数据库事务/CAS替代本工作区的锁与墓碑；持久化任务须跨重启恢复/幂等。共享索引删除失败也先在数据库授权与当前版本检查中阻断旧内容；禁止只相信外部向量服务的候选权限。
4. 服务端身份桥接复用A；共享写请求复用A的write/CSRF；正式上传隔离解析、存储键由服务端生成，原文件/凭据不入Git。接口启用前补足真实权限与MySQL测试。
5. 配置并实测embedding；模型固定配置/索引代次，变更时完整重建；再开放业务API和对应列表/处理/检索/引用/更新/删除页面，完成真实上传闭环验收。

目前没有新增V6 SQL或Flyway迁移。以上清单是接入工作，不能按本日代码直接宣称已完成。测试与本机运行证据见[第三天验证](../verification/member-c-day3.md)。
