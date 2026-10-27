# C 第 2 天开发与验证记录

日期：2026-10-27（北京时间）。分支：`codex/member-c-intelligence`。本日仅在任务全部验证完成后统一提交一次；实现、审查过程不单独提交。

## 范围与环境

承接 [周计划](../team/member-c-week-plan.md) 与 [解析设计](../superpowers/specs/2026-10-27-document-parsing-design.md)。实现 Java 内存解析组件，接昨天的文本切片；不新增上传 API、前端入口、权限或迁移。

Windows；命令进程使用 JDK 21.0.9、编译目标 release 17，未改变全局默认 Java 13 或项目 Java 17 要求。Node.js 24.15.0 / npm 11.12.1；原 lockfile 无变更。本机仍用 H2 演示/测试环境，不把它等同于真实 MySQL。

第 1 天提交 `8ff78e1` 的远端 Actions #40261579954 已核对为 success；这是旧提交结果，本日新提交流水线须另行核对。

## 已运行的开发验证

- 开始开发前 `mvnw.cmd -B -ntp test`：35 项基线通过，无失败/错误/跳过。
- 本日 `npm.cmd run build`：通过（Vite 6.4.5，1688 modules），前端源码和依赖未变。
- TDD 首轮：6 项 DocumentParserTest 均为预期断言失败、0 个执行错误；入口尚未解析，未用文件损坏或编译错误冒充 RED。
- 后续真实文件测试逐项经过 RED→GREEN，包含不计算的公式说明、宏/加密/伪装容器、空白单元格及 BOM/控制字符无正文。
- 中文 PDF 夹具使用 PyMuPDF 合成并渲染检查，用 pypdf 独立提取检查；Java 自动化测试仍以 PDFBox 的真实读取结果验收，不用 Python 解析结果代替 Java 功能。

### 恢复后的最终验证

| 检查 | 结果 |
| --- | --- |
| 宏表修复 RED | 修正未完成样例中空内容类型参数后，7 项均为预期断言失败、0 个执行错误，复现宏表被误接受的问题 |
| 解析/提供方专项 GREEN | 34 项通过，包括新增的 7 项宏表回归 |
| `mvnw.cmd -B -ntp verify` | 69 项通过（原有 35 + 本日 34），0 失败/错误/跳过，可执行 Jar 打包成功 |
| `npm.cmd run build` | 暂停前已通过；恢复后没有改动任何前端源码/配置/依赖 |
| 原有浏览器回归 | 暂停前 5 项全部通过；使用 `artifacts/day2-playwright.config.mjs` 中的同版本 Chromium 141/build1194 无头模式，未改仓库正式配置 |
| 修复后 Jar 启动与健康检查 | Windows 启动成功；`GET /api/system/info` 返回 OK；Flyway 保持 V1，无新迁移 |

独立规格审查在暂停前指出 XLS 的 Excel 4.0 宏表缺口；恢复后重新复现，并在实际 BIFF 记录流同时检查 BoundSheet8/BOF 标记，以及 OOXML 中四种宏表内容类型。相关测试从 7 个断言失败变为全部通过。原先未验证的 DIALOG_SHEET_BIN 样例内容类型为 null，不构成有效文件夹具；修正该样例集合后才记录上述 RED，没有删除已发布的回归测试掩盖失败。

恢复后复核了规格审查问题、来源/限额/资源关闭、失败无部分正文、不可变结果、依赖对齐及公式不执行的代码路径；当前剩余边界为文档中明确列出的不支持格式和进程级资源隔离，不将它们标为已实现。底层库对合成旧 PPT 元数据和中文字体替代会输出警告，不影响测试断言，不宣称日志零警告。

## 公共依赖变更

- PDFBox 3.0.8；Apache POI 的 poi-ooxml / poi-scratchpad 6.0.0，均经官方发布页及 Maven Central 核对。
- POI 6.0.0 最低 Java 17，保持项目 Java/Spring Boot/MyBatis 基线不变。
- 对齐 log4j-api 和 log4j-to-slf4j 到 2.26.1（`log4j2.version`），commons-codec 到 1.23.0；这些是 POI 引入的必要工具/日志版本对齐，需由 A 整合公共 POM。
- commons-io 2.22.0、commons-compress 1.28.0 自然解析，不额外覆盖其版本。排除 PDFBox 的 commons-logging，保留已有 Spring JCL，避免重复发现。
- 不降低 POI 的 ZIP 安全阈值，不加入公式计算、外部关系访问、宏执行或网络服务。

依据：[PDFBox 发布](https://pdfbox.apache.org/download.cgi)、[POI 发布](https://poi.apache.org/download.html)、[POI 6.0.0 变更](https://poi.apache.org/changes.html)。

## 实际边界与未完成项

支持范围、来源和结果码见 [解析接口说明](../modules/c-parsing.md)。旧 DOC、宏文档、真实 OCR/转写/embedding、向量检索、AI、报表仍未交付。提供方接口存在不等于外部能力已接入。

本次只提交合成样例 `knowledge/unicode-three-pages.pdf`，未提交用户的 10MB 实训原文档、真实员工信息、凭据或本机数据库。其第 2 页为空，验证第 3 页来源不被压缩成第 2 页。

A 的员工/范围接口、B 的规则/本人事件接口已出现在个人分支，但 main 尚未合并，授权日报和审批调整尚缺；C 仅读取协作文档，不直接复制未合并实现。不越过 V2～V5 应用 V6。

同步后最终 SHA 与 CI 结果以 PR #1 和交付报告为准；不能将本机测试通过等同于远端流水线已通过。


## 暂停检查点（历史记录，2026-10-27；后已恢复）

- 用户明确要求暂停，已停止实现代理及本轮演示后端；所有已写入代码、测试和文档保留在本地，未执行日终 commit/push。
- 当前分支 codex/member-c-intelligence，远端/本地已提交基线仍为 8ff78e1；今天的改动处于工作区，包含未跟踪的新文件，关机不会自动删除。
- 暂停前已完成一次全量 `mvnw.cmd -B -ntp verify`：62 项通过并打包成功；前端构建通过；原有浏览器回归 5 项通过（临时忽略配置 artifacts/day2-playwright.config.mjs）。
- 用本地临时 Java 程序读取用户提供的实训 PDF：SUCCESS，10116058 字节、23 个来源段、21 个含中文来源段、22 个切片。原 PDF 和临时工具均不提交。
- 上述 62 项结果发生在新增 Excel 4.0 宏表回归之前，不能当作当前最新工作区全部测试已通过。
- 独立规格审查发现 P2：旧 XLS 的 Excel 4.0 宏表可绕过 VBA 存储检查。实现代理正在添加 MacroSheetSafetyTest（BIFF BoundSheet/BOF 元数据，以及 OOXML 宏表类型），用户此时暂停，修复与新测试尚未最终确认。
- 恢复后先检查 MacroSheetSafetyTest、FormatDetector、OfficeTextParser 的实际落盘内容，完成宏表拒绝逻辑及 RED/GREEN；规格复审通过后再做独立代码质量审查，然后重跑受影响的全量检查。不要直接使用旧测试结果提交。
- 完成当天全部剩余任务后统一提交一次并更新 PR #1，不自动合并、不 force push；继续保留原 B 草稿 stash。
