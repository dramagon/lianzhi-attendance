# A 三人整合复验 · 2026-11-06

在个人分支 `codex/member-a-release-validation` 整合 B `8b12018`，该提交已经包含 C `9ceee3a`。保留 A 的真实地图记录、MySQL 验收及 follow-redirects 1.16.2 修复。

唯一文本冲突为报表测试的数据源环境变量。兼容 A 的 `LIANZHI_REPORT_TEST_URL` 与 C 的 `LIANZHI_C_REPORT_TEST_URL`，A 配置优先，默认仍使用原 H2 夹具。未修改业务断言或已发布迁移。

Windows 本机 JDK17、MySQL8.0.28 复验结果如下。

- 完整后端 verify 346 项全部通过，JAR 打包成功。
- 前端按锁文件安装、format、主站 build 和 C 模块独立 build 全部通过。报表模块分块约914kB，构建存在大分块提示。
- 独立8081 H2演示后端和5197前端，完整浏览器46项全部通过，无跳过。覆盖组织、头像、权限、审批、扫码考勤、设备请求生命周期与课堂展示入口。原有查看环境保留。
- `lianzhi_verify` 独立 MySQL 库执行V6迁移，KnowledgePersistenceTest 10项和AttendanceReportsTest 13项全部通过。正式业务库未应用V6。
- 迁移顺序为1、2、3、4、4.1、5、6。C的知识库持久化、后台队列、检索、模型与媒体接口及B课堂演示暂停/恢复代码均已整合。

本轮未在本机重跑外部模型、OCR、音视频及Docker容器。C的真实服务和云端容器证据见 `member-c-completion.md`。真实人脸SDK、手机现场定位与扫码、Kubernetes等继续按共同验收范围落实。

用户要求最终交付前移除页面中的建设进度入口、进度页和面向用户的开发阶段提示，作为最终交付检查项保留。

本机日志位于忽略目录 `artifacts/a-nov6-*.log`。密钥、数据库与测试资料留在本机。
