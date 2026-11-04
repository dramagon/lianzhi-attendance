# C 第四天：整合与助手契约

2026-10-29。整合分支codex/member-c-day4-integration，双亲为C d91439e、A 182d248（含B e3eafe6），不合并main。原生Git网络超时，改由GitHub插件读取并校验原始对象SHA后整合，未改写A/B历史，无文本冲突。见[设计](c-day4-design.md)、[验证](../verification/member-c-day4.md)。

本文件保留第四天边界。2026-11-01已新增授权汇总和需用户明确确认的申请提交；当前写接口、writeAvailable含义及剩余限制以[第五阶段契约](c-day5-actions.md)为准，模型问答仍未开放。

## 当前实际边界

已对接A当前身份/公司权限、B日考勤、A真实审批；主站接入知识库关闭态与助手只读查询。昨日C对“B没有日服务”的判断有误，今日按真实代码纠正；A也已交付V4/V4.1。个人整合分支使用独立验证库运行V1/V2/V3/V4/V4.1，main未合并这些能力。V5仍缺，不越序启用V6。

真实模型、embedding、OCR和转写尚无配置，不能宣称实际服务联通或问答质量通过。内部RAG与内存工作区没有注册为生产Bean，没有真实知识库存储、上传、会话持久化或向量数据库。模型配置齐全不等于服务可用。

## 认证与HTTP

`KnowledgeIdentity.current(Authentication)`通过A的DataScope、EmployeeService和OrganizationMapper每次读取当前数据库身份/角色、本人档案及启用公司。非ADMIN无公司、公司停用、员工停用时拒绝；不绑定请求/模型中的调用者身份。保留A会话版本撤销规则和调公司后的新范围。

| 方法/路径 | 实际行为 |
| --- | --- |
| GET `/api/assistant/status` | 登录后返回knowledgeAvailable=false、businessQueriesAvailable=true、writeAvailable=false；模型/embedding为NOT_CONFIGURED或CONFIGURED_NOT_VERIFIED，OCR/转写为NOT_CONFIGURED。不是外部服务健康检查。 |
| GET `/api/assistant/attendance?from=&to=&userId=` | 默认本人；直接调用B `AttendanceDays.list(Authentication,userId,from,to)`，最多94天；普通员工本人、HR本公司、ADMIN职责范围。返回B的Day原结构及attendance/leaveMinutes/overtimeMinutes/approvalIds，不重新计算工时或直接读AttendanceStore。 |
| GET `/api/assistant/approvals?state=ALL&page=1&size=20` | A的本人分页查询，size≤100；真实items/total/page/size。 |
| GET `/api/assistant/approvals/{id}` | 直接复用A的详情/审计授权，本人或有权管理者。 |
| POST `/api/assistant/ask` | 登录/CSRF后503 ASSISTANT_UNAVAILABLE、data=null；缺少持久化知识来源，不请求模型。 |

沿用ApiResponse、Session、CSRF；匿名401、越权403、参数400。B Java日期范围错误在C专属Advice转换成固定中文400，不改A/B处理器。助手没有写申请、审批或模型工具执行接口。真实日记录为空表示没有排班，不能推断缺勤为零。

`/assistant`显示本人考勤与本人前20条申请及总数，链接A的完整申请页，来源分别标明考勤日记录/本人申请；失败显示错误，无静态数据兜底。制度问答禁用并解释未接入；`/knowledge`保留关闭态。根路由/菜单只增加C入口，保留A/B全部导航，后端继续鉴权。

## 内部带来源问答

`CitedAnswerService(Sources,CitedModel).ask(Authentication,question)`不是应用Bean。Sources没有生产实现，必须在后续V6接通后，每次依据当前数据库身份、文档范围和版本检索/读取引用；不能信任请求传入的Actor。

- 已认证且非匿名；问题规范化后1～1000码点，原始UTF-16≤4000。
- 检索最多6个已授权切片；无依据NO_EVIDENCE，不请求模型。模型前重读引用，完整比较ID/版本/切片/正文/来源；每片≤800码点。
- 本次生成S1～S6来源ID，模型只收到问题及正文；不发送公司/账号/凭据/服务地址。系统规则独立为system消息，问题/来源用JSON作为不可信数据发送，无tools定义。
- 成功回答必须非空且≤6000 UTF-16单元，并含1～6个不重复的已提供来源ID及其非空连续原文摘录；未知引用、伪造摘录、重复引用均拒绝。
- 模型返回后再次校验所有曾提供来源，修改/删除/撤权时丢弃整个回答。后续读取引用也必须重新鉴权。
- 状态：ANSWERED / NO_EVIDENCE / NOT_CONFIGURED / UNAVAILABLE / INVALID_RESPONSE / STALE_SOURCES；失败没有答案正文或部分引用，错误不泄漏提供方诊断。

逐字摘录及ID校验不能证明模型所有语义推理正确，系统提示也不能保证抵御所有注入。后端没有把权限或写工具交给模型；真实模型启用后仍须验收事实一致性、恶意资料与拒答质量。合成HTTP响应只证明协议/来源约束，不代表真实模型验收。

## 模型协议与环境配置

`OllamaCitedModel`真实调用 `/api/chat`，stream=false、format=json、temperature=0、num_predict=1200；依据[官方Chat协议](https://docs.ollama.com/api/chat)及[结构输出](https://docs.ollama.com/capabilities/structured-outputs)。不安装服务、下载模型或消耗外部账户额度。

IntelligenceConfiguration读取以下可选环境变量，默认空；只支持无密钥Ollama协议，不承诺兼容其他商业API或Bearer认证。密钥不要发聊天或提交Git，后续按选定服务正式协议另接。

```text
LIANZHI_AI_BASE_URL=<已部署Ollama的HTTP(S)根地址>
LIANZHI_AI_MODEL=<服务实际返回的准确模型名/标签>
LIANZHI_EMBEDDING_BASE_URL=<已部署Ollama的HTTP(S)根地址>
LIANZHI_EMBEDDING_MODEL=<准确embedding模型名/标签>
```

地址禁止凭据/路径/查询/片段，不能来自浏览器或文档；不跟随重定向、不重试。模型默认整体超时10秒（含慢响应体），构造函数可设1毫秒～60秒；响应接收过程中限制256KiB。输入问题+来源≤14000 UTF-16单元，不等同token预算，须另核对模型上下文/硬件资源。

校验HTTP200、准确模型名、done=true、assistant角色、结构JSON；tool_calls存在则拒绝，不执行工具。模型适配器SUCCESS仅表示结构通过，最终引用仍须CitedAnswerService校验，不能直接作为HTTP答案。真实OCR/转写仅保留第二天接口，未接供应商，不生成文字/时间戳冒充成功。

后续：V5与团队迁移顺序就绪后实施V6、隔离解析、持久化来源/索引与Sources桥接，再开放上传与制度问答。AI写申请仍需确认、稳定requestKey、A正常服务重新鉴权与幂等；本日只读查询不包含这些功能。最终合并由A执行。
