# 三人整合验收 · 2026-11-04

以 C 报表分支 `2ec18ad` 为基线。该提交已包含 A 的地图验收 `d6ce1b7`、B 的课堂包与 V5 `5c21217` 及 C 的申请确认 `b56f1a4`。在独立工作区验收，整合 PR 目标为 `main`。

Windows 本机使用 JDK 17.0.12 和 MySQL 8.0.28。Flyway 在已有的 `lianzhi_verify` 验证库从 V4.1 升到 V5。升级和报表测试前后，账号、原始打卡和申请分别为 37、12、14 条。正式库未执行迁移。

真实 MySQL 报表专项 13 项全通过。用合成数据核对了 B 的日记录与报表明细、汇总及趋势，验证请假分钟与审批来源、跨夜班次、导出范围与 CSV 内容、员工越权及 HR 跨公司限制。保留了原有断言。报表测试默认使用 H2，设置 `LIANZHI_REPORT_TEST_URL` 以及 `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 可指定独立 MySQL 验证库。

本机默认后端 `mvnw.cmd -B -ntp verify` 共 317 项通过并生成 JAR。前端 `npm ci`、`npm run build` 和 C 模块独立构建通过。npm 安装报告 1 项高危依赖告警，留待合并前复核。完整浏览器回归沿用 C 基线的 38 项和地图仿真 4 项记录，本轮未重跑浏览器。

真实手机定位与扫码、实际人脸 SDK 及授权、知识库持久化与真实模型、容器部署仍待各自验收。
