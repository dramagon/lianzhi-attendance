# B配合任务验证

## 缺陷与改进

1. 选图等待期间只按sessionEpoch检查返回，换页或撤回同意后旧图仍可能回填。新增独立采集版本、明确取消和H5 AbortController，清理选择器/监听器；回调须同时匹配账号、页面、同意和采集票据。系统相机临时离开页面是受约束的采集返回，不会自动上传，后台原定位/凭据仍被清除。
2. 待核对编号原先在档案加载完成后才恢复；档案503会漏恢复。现登录/会话确认即按本人ID恢复，档案刷新另有版本隔离，避免过时结果覆盖。
3. 增加H5 pagehide/visibilitychange清理，取消等待与后台迟到识别不恢复有效凭据。实际写入中的后台中断仍保持待确认，只查原回执、不补发。
4. 增加uni-app设备验收页，默认10项NOT_RUN；填写实际设备和实测SHA，含真实人脸凭据打卡PASS需填事件编号供A核对。H5下载、本地微信文件生成/用户主动分享；离线可用，同账号切页保留草稿，退出/刷新不承诺保留。观测只白名单4个bool，不导出Cookie、坐标、口令、图像或验证凭据。结果明确是操作者自报，不是服务端认证。

## 移动端与工具实测

- 协议及报告单测11项通过。增加C Redis 503不清Cookie、不重放、未执行不假通过、报告字段隔离及证据编号校验。
- 全部H5浏览器11项通过：原5项、撤回/切页取消2项、后台晚识别1项、真实写入时后台并查回执1项、手工报告及草稿1项、服务离线报告1项。定位是明确仿真；人脸页面成功响应只在测试SDK契约替身，不是现场真人结果。实际业务打卡使用独立演示库真实API，只有1条事件；档案503故障时仍恢复待确认。
- H5和mp-weixin真实构建通过；小程序原生拍照、文件分享、HTTPS/微信域名和手机定位本轮未运行。
- SDK静态检查4项、课堂打包检查7项通过。Python工具编译及JS语法、git diff --check通过。本轮未改主站业务/后端/迁移，未重新声称主站52或后端365完整回归。

日志：/tmp/b-cooperate-unit.log、/tmp/b-cooperate-e2e-final.log、/tmp/b-cooperate-h5.log、/tmp/b-cooperate-mp.log。截图artifacts/b-mobile-acceptance-report.png。首次离线用例故障注入误匹配uni的style/api资源，改为只拦pathname以/api/开头后完整通过，未删改业务断言。

## B/C真实组合验证

读取C692d90c、保留B50c0a40成果，不合并C；C源码在独立/tmp工作区构建JAR（该准备构建跳过C单测，不当作C完整测试）。使用C的现有DisposableMysqlContainer、DisposableRedis及Applications夹具，只读复制到B ignored证据目录。精确镜像摘要、C SHA与实际B client.js SHA256写入报告。

真实MySQL8.4.13、真实Redis、两个独立JVM，B原生会话客户端经Node HTTP传输适配器交替发送25次请求（13/12），包括两种角色登录、跨实例Session/CSRF、正式排班、有效二维码、普通打卡、两节点同回执、本人原始/日记录、退出与401；直接SQL验证1个原始事件，Redis中3个会话相关键，最终passed=true。没有JWT、生产库、SDK成功替身或直接插入假事件。Cookie不输出/不持久化，报告只有数量与bool。

证据：artifacts/b-c-session-6e5b53c7a12a/result.json、client.log、first.log、second.log；入口/tmp/b-c-session-final.log。初次客户端全部通过，但统计SQL未指定数据库而使总报告失败（artifacts/b-c-session-ad4836c7f46e）；将统计限定为c_verify.attendance_event后新建全部隔离资源重新通过。未修改C实现或放宽断言。所有自有容器/JVM已停止，A和已有服务未操作。

这是HTTP适配器对真实后端/MySQL/Redis的会话协议组合验证，不是微信原生运行、TLS、真实GPS或Windows SDK验收。真实人脸同人/异人/照片/屏幕及同一笔含凭据打卡仍由A现场逐项核对。现场报告也不能替代这些证据。

## 复现与交付

先在独立C工作区构建其JAR，使用scripts/verify-b-c-session.py --help提供C checkout/JAR、Java和两个精确镜像摘要。脚本创建独占临时MySQL/Redis与2 JVM，不接受现有数据库地址；Node适配器只允许本隔离入口，不要手工伪造环境改指A业务库。Docker/Java/Node按已有环境准备，C工具由传入工作区提供，锁定报告中的C SHA与镜像即可复现。

源码和构建包见最终汇报，仍推送codex/member-b-cloud-finish，PR目标codex/member-a-release-validation。A审批/最终合并与现场设备不由云端伪造通过。

打包检查同时修复预览输出可能沿用正式提交文件名的问题：dirty构建改用-working后缀，已交付50c0a40按原提交在独立工作区重建并保留干净manifest；最终新包按新提交命名。新增移动打包3项测试通过，确认预览不覆盖正式包、文件哈希正确、私有开发配置及源映射排除、小程序本地组件依赖完整；生产包包含新增验收组件的js/json/wxml/wxss，缺引用会在写包前拒绝。
