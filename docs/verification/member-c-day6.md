# C 第六工作阶段验证 · 2026-11-04

依据团队任务、契约及已读取的23页实训PDF，继续第六工作阶段（原排期10/31顺延），当天工作完成后统一提交。第七阶段及全部C任务未完成，不因日历日期跳过验收。

## 前置整合

GitHub插件读取完整分支和PR列表：main为67969ac，A地图为d6ce1b7，C第五阶段b56f1a4；B更新到5c21217。PR #4已于11/03合入B，其余既有PR仍未合入main。B新增正式V5、DATETIME跨时区读取修复及课堂包，详见B的11/03记录。

从C第五阶段创建codex/member-c-day6-reports，原生Git获取B最新对象后no-commit整合；README、首页、菜单和既有foundation测试重叠按实际功能解决，保留C入口和B更精确的exact断言。正式V5由B发布，旧候选文件删除；未修改已发布V1/V2/V3/V4/V4.1。既有A/B/C业务代码保留，C新增report包、报表页和deploy配置。

## 后端与构建

- Windows JDK21.0.9、编译release17，最终`mvnw.cmd -B -ntp verify` **317项、失败0、错误0、跳过0**，JAR打包成功，日志backend/target/day6-final-verify.log。
- C新增AttendanceReportsTest **13项**：真实B字段与行/汇总/趋势/场所一致；零排班分母为空；跨夜按业务日和真实班次结束；待结算/休息/缺勤/缺卡区分；全日/部分请假及审批来源；分页/筛选/全范围CSV；公式/引号/控制字符；员工拒绝；HR跨公司/部门/员工/场所拒绝；转岗历史场所拒绝；停用账号；日期/人员/分页限额；HTTP权限及CSV响应头。
- 首次针对测试12项中1项夹具错误（试图将场所company_id设NULL，违反真实非空约束），修正为真实另一公司后通过；没有修改约束或放宽业务断言。随后全量316项通过，补跨夜测试后最终317项通过。
- frontend格式化、主站build、独立C模块build通过，日志frontend/artifacts/day6-final-{format,build}.log及day6-final-module.log。没有增加npm/Maven依赖。
- 既有A审批并发测试本轮通过；第五阶段云端首次失败仍有历史记录，本轮通过不代表其潜在竞态已修复。

## 浏览器与下载

启动本轮独立H2内存库`c-day6-browser`，实际执行V1/V2/V3/V4/V4.1/V5；使用受控TTY Vite5195，避免旧阶段开发服务器退出问题。不访问或迁移原有业务文件库。测试只使用合成账号、场所与申请，真实SDK仍关闭。

- 完整既有浏览器 **38项通过**，包括新增B课堂页在普通环境明确关闭（不宣称本轮重新验收完整课堂包）。日志frontend/artifacts/day6-e2e.log。
- 地图SDK仿真 **4项通过**，使用A已有独立配置/合成凭据，日志frontend/artifacts/day6-map.log，不宣称真实高德或真机验证。
- Playwright CLI真实登录管理员，报表读取B合成打卡数据，`GET /api/reports/attendance`与B `/api/attendance/days`的同userId/日期状态及工时一致，未结束班次分母0、出勤率“不适用”。
- 实际点击CSV下载，取得attendance.csv；Python csv读取UTF-8 BOM，共19列、表头加1条真实合成日记录。没有仅检查下载按钮存在。
- 延迟一次真实报表响应，改变员工筛选再放行：旧结果未显示；再次查询按新员工范围返回。没有通过伪造成功数据测试统计。
- 员工实际登录后访问/reports进入无权页，直接CSV接口403；HR实际登录可查询。390×844页面scrollWidth≤innerWidth，桌面1440×1000截图已查看。初次截图发现全局SVG样式将图表压扁，添加局部高度规则后复查高度130px，截图正常。
- 截图：artifacts/output/playwright/day6-reports-mobile.png、day6-reports-desktop.png、day6-hr-mobile.png；CSV在同目录day6-attendance.csv，均Git忽略。截图关闭CSS过渡，避免把菜单移动中的帧误判为静态布局。

## 部署与剩余

Compose通过PyYAML解析并核对mysql/backend/web服务、数据库及后端无宿主机端口；敏感.env不进入Git或Docker上下文，未填写/输出真实凭据。**本机无Docker命令，未运行Compose验证器、镜像、Nginx或容器MySQL**。没有将YAML可解析当成容器可运行。

报表及导出未执行真实MySQL联验，B此前MySQL结果只作为上游记录，不替代C本轮验证。场所图仅为分坐标系相对分布/出勤热度，不是连续地图热力；ECharts、云效、Redis、调度、Nacos/OpenFeign、Kubernetes、预测的未完成状态列在[部署说明](../../deploy/README.md)。

正式V5已整合，但团队main尚未按序合并；V6知识库持久化、实际上传、真实模型/embedding/OCR/转写、部署和最终报告仍待下一阶段。远端SHA、草稿PR和云端Java17/Node22结果在PR及最终报告中核对；本文不预先宣称远端通过。原分支与两份stash保留，最终main合并由A执行。
