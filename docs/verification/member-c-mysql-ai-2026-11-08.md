# C 第一阶段：正式 MySQL 与真实 AI 联调

2026-11-08，Windows，独立工作区 `lianzhi-c-cloud-finish`。基线 A `1562571f5558df3aa9bef1822c4886476ba3df40`，收尾分支 `codex/member-c-cloud-finish`，PR 目标 A 整合分支，未合并 main。原 C 工作区及其分支没有切换或覆盖。

## 改动

完整本地 AI 启动器原来固定使用 H2 演示配置。新增显式 `--mysql`，将真实 Ollama/embedding、OCR/Whisper 与非演示 MySQL 后端组合启动，支持配置运行时、模型、Java/Python/FFmpeg、数据目录和端口。保留 A 的 start-ai 入口、SDK 环境继承和课堂库复用；MySQL 模式不复用 H2，不生成演示账号，默认数据目录分开。无凭据、错误数据库类型及端口冲突均提前拒绝；配置检查不会启动服务。

发现真实小模型对完整请假指令仍生成 endAt=null，已有业务校验返回400且没有落库。生成约束现按 LEAVE/OVERTIME/MISSING_PUNCH 分支限定字段，向模型同步提供格式说明；新增提供方忽略约束时的拒绝和无副作用回归。后端正常鉴权、确认、CSRF、业务校验和数据库幂等保持原样。

新增可复现合成资料生成器、独立 MySQL/模型组合验收脚本、Windows/Linux 配置边界 CI 和配置样例。没有新建迁移或替换框架依赖。

## 本机实际通过

| 检查 | 结果与证据 |
| --- | --- |
| 后端完整 verify 与同源打包 | `backend-final-verify.log`，Java21编译release17，363项，失败0、错误0、跳过1；跳过项为未配置授权的 ArcSoftFaceProviderTest，362项实际执行通过 |
| 前端生产构建 | `npm run build`通过，`npm ls --depth=0`完整；仍有既有约600KB报表包体积提示，没有隐藏 |
| 启动边界 | `python -m unittest discover -s scripts -p test_c_runtime.py -v`，7项通过：数据库/演示隔离、凭据、字面配置、端口与已有监听保护、媒体关闭态 |
| 包结构 | 同源 index.html、正式V6在JAR中；未包含测试类；最终实跑使用打包后的独立JAR副本 |
| 真实服务完整验收 | `artifacts/c-mysql-ai-b9efbb9fd568/result.json`，passed=true；本次创建独立 MySQL8.0.41实例、非演示管理员和两个合成公司/员工，无真实员工资料 |
| 迁移与文档 | V1/V2/V3/V4/V4.1/V5/V6新库迁移；DOCX后台子进程解析、原件下载、TEXT/VECTOR检索、当前版本来源核对通过 |
| 真实模型问答 | Ollama CPU `qwen2.5:1.5b`，回答年假每年五天，引用本次上传的私有文档；`qwen3-embedding:0.6b`真实向量索引/查询通过；没有固定回答或HTTP替身 |
| 真实OCR和转写 | 新生成的合成PNG、无文字层PDF和系统中文TTS WAV，经网页同一上传API进入MySQL，PARSED/READY并可VECTOR检索；来源分别为PAGE第1页及TIME_RANGE 0～6000毫秒 |
| 建议与确认 | 修正后真实模型ready=true，正确保留2026-11-09 09:00～18:00、请假和个人事务，建议和预览不增加申请；明确确认后只有一份申请，重复确认返回同一结果 |
| 同口径业务 | 正式API安排未来班次并审批合成请假；本人考勤与助手日记录完全一致，报表/助手汇总均为480分钟，审批来源ID一致；未伪造原始打卡 |
| 故障恢复 | 实际停止本次Ollama，问答返回UNAVAILABLE且无正文/引用，原文来源仍可读；恢复提供方后向量查询恢复 |
| 双重重启 | 实际停止应用并关闭/重开本次MySQL，再启动应用；文档指纹、原件、来源和申请保留；数据库requestKey重放不重复创建；原会话和确认草稿失效 |
| 任务恢复 | 仅在新合成库模拟过期PROCESSING租约，应用实际调度器标记FAILED；用户显式retry产生新版本，解析/向量重新READY |
| 权限/限额 | 跨公司文档/来源不可见、检索无命中、问答NO_EVIDENCE；跨用户考勤403、缺CSRF403、501字问题400、媒体未授权401；公司变更撤销旧会话并收回来源权限 |
| 删除与退出清理 | 删除后旧来源404且检索不再返回该文档；本次进程全部退出，临时数据库密码配置删除，系统MySQL服务未连接/停止，原业务资料未动 |

真实OCR识别本次两行合成文字正确；Whisper有“员工→元工”“须由→虚有”等误字，不能描述为转写准确率通过。原文观察保存于同目录 `media-text-observations.json`，所有内容均为合成制度和系统语音。模型样例通过也不代表任意申请指令都可靠，保留人工核对和拒绝分支。

## 实跑中发现的问题与证据边界

首次离线前端安装缺fflate缓存，随后补齐依赖并构建成功；不将首次失败记为通过。验收脚本初版误用已撤销会话、试图补建历史排班，已改为验证401后新会话登录，以及通过正常服务创建未来排班。曾在运行中的JAR上同时打包导致Windows文件锁/加载失败，现对每次验收复制独立JAR，最终包和完整实跑均通过。上述失败结果保留在独立忽略目录，没有删除用例或放宽业务权限来绕过。

真实模型漏结束时间的失败记录在 `artifacts/c-mysql-ai-d51097e40be6/result.json`；该轮其余文本/业务/恢复验收完成，但明确保留建议生成限制。最终 `b9efbb9fd568` 使用修正后的新包和新合成媒体完成全链路。协议替身单元测试与这里的真实推理分开统计。

没有改登录/导航或前端页面，本轮未重复本机浏览器全量；远端既有完整工作流仍保留。提交时远端CI尚未运行，具体链接和结果在PR/交付回复核对，不预先标记通过。

## 首次远端回归与 H2 缓存修正

首个提交 `cea9e1c` 的[MySQL容器验证](https://github.com/dramagon/lianzhi-attendance/actions/runs/42470912906)及[Windows/Linux启动配置](https://github.com/dramagon/lianzhi-attendance/actions/runs/42470913147)通过；[完整回归](https://github.com/dramagon/lianzhi-attendance/actions/runs/42470913108)在既有 `ApprovalWorkflowTest.concurrentIdenticalApprovalIsIdempotent` 失败。两个相同审批应返回同一结果，实际其中一个拿到旧PENDING状态并触发版本冲突，后续浏览器步骤未运行。

本机将原用例增强为50次重复执行后复现1次失败，证据 `approval-race-repeated-before.log`。路径与 [H2 2.3.232 官方问题 #4208](https://github.com/h2database/h2database/issues/4208)相符：加锁前后相同SELECT可能复用过期结果。采用上游给出的 `OPTIMIZE_REUSE_RESULTS=FALSE`，只附加到H2演示/测试URL及已有课堂/移动验收覆盖URL。MySQL连接配置和审批业务逻辑没有改动，也没有升级依赖或修改断言。自定义H2 URL应同步添加此项。

修正后同一并发用例50次全部通过，`approval-race-repeated-after.log`。新增的重复执行保留在回归中；启动边界7项、PowerShell解析及Python编译检查通过。随后完整verify与同源打包412项，失败0、错误0、虹软条件专项跳过1，411项实际执行通过，证据 `backend-h2-cache-verify.log`。新增49次重复执行计入该总数，不将其当作49个不同业务场景。远端重新验证结果在最终PR核对，不用首次失败的运行冒充通过。

## 剩余范围

- Redis共享Session、多实例会话和序列化为C2，尚未实现；当前重启失效是既有单实例模式的预期行为。
- Nacos/OpenFeign为C3；容器/备份恢复、K8s/云效及生产部署为后续阶段，不因本次Windows独立实跑自动记为完成。
- Windows真实虹软、手机GPS/摄像头及A现有正式业务库的完整现场组合仍由A验收；本轮未加载其授权或触碰其数据库。
- Linux目前交付跨平台路径与配置测试工作流，不宣称Linux完整模型/媒体服务已实跑。操作入口及依赖见[组合启动说明](../modules/c-mysql-ai.md)。
