# C 第四天与A/B整合验证

日期2026-10-29，Asia/Shanghai。范围见[设计](../modules/c-day4-design.md)、[助手契约](../modules/c-assistant.md)。C原提交d91439e，A最新182d248（已包含B e3eafe6）；main仍67969ac。独立分支codex/member-c-day4-integration保留两条历史，最终草稿PR以A审批工作流分支为基线，供A评审最终整合。

## 同步与依赖事实

- GitHub插件读取最新分支、PR、A/B接口和交接说明。A已实现真实审批及V4/V4.1，B已有日服务/正式V3/审批调整；此前C对B未实现日服务的描述不准确，本次纠正，不把历史文档当作当前事实。
- 原生git fetch因网络低速超时失败；改用GitHub插件读取原始树、缺失文本blob及提交元数据，逐个验证Git对象SHA后导入已有对象库。重建的是远端原始提交，不是重新提交A/B代码；A分支已经包含B，无需重复合并B。
- 在新C整合分支以no-commit合并A，保留所有A/B源代码、迁移和测试，无文本冲突。中途没有提交；最终一次提交包含C和A两个父提交。旧C分支及两份stash保留，未改写main或他人分支。
- V5仍未提供，不启用V6；没有修改V1/V2/V3/V4/V4.1原文，也没有开启out-of-order。新独立H2内存库实际依序应用五个迁移至4.1，没有升级或清空用户原有演示库。

## 后端验证

Windows本机JDK21.0.9，编译release17，`backend/mvnw.cmd -B -ntp verify`最终258项全部通过，失败0、错误0、跳过0，Jar打包成功。日志在忽略目录backend/target/day4-final-release.log，逐项可核对Surefire XML。

首次整合基线226项通过，完整保留A/B与C已有测试。本日新增32项：

| 类 | 数量 | 验证内容 |
| --- | --- | --- |
| CitedAnswerServiceTest | 12 | 来源ID/逐字摘录、无依据不请求模型、未配置、未知引用/空/伪造摘录、拒答、不返回部分错误、模型前撤权/后撤权、同ID版本正文变化、输入/认证；真实三页中文PDF解析→工作区检索→第三页引用。 |
| OllamaCitedModelTest | 11 | 本机随机端口真实HTTP协议、system与不可信JSON分离、无tools、结构输出/模型/类型校验、拒绝tool_calls、错误不泄漏、响应体上限、缺配置/非法地址、连接失败和慢响应体整体超时。仅协议夹具，不是实际模型。 |
| AssistantIntegrationTest | 9 | A认证/公司桥接，停用/无公司/调公司，B与C日查询逐字段相等，员工伪造目标及HR跨公司拒绝，同公司HR读取，A与C真实申请/审计相等及他人拒绝，诚实状态/CSRF/503、非法参数、真实SessionUser版本撤销。 |

验证中发现并修复：B的Java日期范围IllegalArgumentException没有被C新控制器的Advice覆盖，首轮257项出现1个Servlet错误。新增仅作用于AssistantController的AssistantErrors，把参数异常映射为400固定文案；没有修改B规则、日期范围或放松断言。之后257项通过，再补慢响应体测试，并完成最终258项验证。

自行复核只读接口委托A/B服务、没有直接读取B存储、没有模型写操作；引用在网络前后核验，非ANSWERED不返回正文。模型语义正确性和所有提示注入抵抗能力不在合成响应测试的证明范围。没有独立代理审查，不宣称外部审查通过。

## 前端、浏览器和迁移

- npm ci完成，使用A/B原锁文件，无新增依赖；npm run format、npm run build（1770模块）、独立build-intelligence.mjs（7模块）通过。日志在frontend/artifacts/day4-{npm-ci,format,build,module-build}.log。
- 原有全量浏览器25项通过、无跳过，含组织/头像/认证、A真实申请→审批→B请假来源、B二维码真实图片解码→浏览器仿真定位→上下班→日记录、手机异步交互与设备拒绝路径。完整日志frontend/artifacts/day4-e2e.log。没有删除或修改测试断言；使用既有忽略目录Playwright配置指定Chromium1194。
- 演示Jar使用新`jdbc:h2:mem:c-day4-browser`独立库启动，Flyway实际执行1/2/3/4/4.1。测试数据均合成，只存在本次测试库；头像测试输出在忽略目录。无用户数据迁移/删除，不宣称真实MySQL、真机或人脸SDK验收。
- Playwright CLI额外实际登录演示员工，验证`/assistant`认证重定向、新菜单、未配置/禁用提问、本人考勤无排班说明、本人真实申请查询（读取A浏览器用例创建的已撤回申请）、申请审计页链接。非法94天以上范围显示真实400中文错误，再查询恢复；日期改变清除旧考勤结果。
- `/knowledge`已在主站显示关闭态，持久化未接通、按钮禁用。没有只依靠临时预览HTML宣称已整合。
- 桌面1440×1000、手机390×844截图均已查看，手机scrollWidth≤innerWidth。图片在忽略目录artifacts/output/playwright/day4-assistant-{desktop,mobile}.png，CLI快照在artifacts/.playwright-cli。
- 检查完后停止本次启动的演示后端、Vite和CLI浏览器。旧演示数据库文件未被本次进程打开。

## 已通过与待完成

已通过的是A/B/C联合构建、现有业务回归、C身份/只读服务/菜单页面、内部带引用问答与真实HTTP适配的协议约束。模型/embedding环境变量装配已经实现，但目前没有服务地址/准确模型名，因此没有真实外部调用；OCR/转写仍只有可替换接口和明确未配置状态。

尚待真实模型/embedding/OCR/转写配置与单独验收、B V5/团队迁移顺序、C V6/隔离解析/持久化来源和索引、正式上传/制度问答、AI写操作确认幂等、报表/导出及部署。不能把本日交付表述为第四天所有外部能力或完整AI已完成。

GitHub远端SHA、PR与云端Java17/Node22 CI结果以本次最终交付和PR为准，本记录不预先宣称远端通过。按用户要求，当天全部验证结束后一次提交；main最终合并由A执行。
