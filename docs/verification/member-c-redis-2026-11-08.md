# C2 Redis共享Session验收（2026-11-08）

基线A `1562571`，个人分支 `codex/member-c-cloud-finish`，继承C1 `75255b5`；交付至[PR #13](https://github.com/dramagon/lianzhi-attendance/pull/13)，由A审阅整合。配置、故障语义及复现命令见[Redis说明](../modules/c-redis-session.md)。本记录只代表列出的实际运行范围。

## 已实现

- 显式开关，默认关闭；关闭时保留原进程内Session，不因添加依赖自动连接Redis。开启时使用Spring Session、基本RedisSessionRepository及有界Lettuce超时。
- 沿用JSESSIONID、HttpOnly、SameSite、Secure配置、CSRF、登录旋转ID及数据库auth_version撤销。共享登录不改变A/B接口或迁移。
- 身份与确认草稿可序列化；缓存前清除认证密码，不缓存UserAccount持久对象或密码散列。状态修改显式setAttribute，确认/取消增加跨实例原子状态；业务防重仍由A的MySQL requestKey保证。
- Redis不可用时启动失败或运行中返回503/SESSION_STORE_UNAVAILABLE，不退回本机登录。正常AOF重启保留会话；未验证断电或高可用恢复。
- 提供可选Compose叠加配置、固定Redis镜像及不含明文密码参数的ACL启动脚本；AI启动器只向Java传递数据库/Redis/SDK凭据，不向模型或媒体进程传递。

## 本机实际结果

Windows Java21编译release17；MySQL8.0.41独立新库；Ubuntu24.04 WSL中真实Redis7.0.15。通过apt-get download和dpkg-deb解压官方Ubuntu包运行Redis，没有安装系统服务。所有账号、申请和文件均为合成资料，没有访问用户既有MySQL数据目录或停止其服务。

| 范围 | 结果与证据 |
| --- | --- |
| 最终完整后端verify | 420次执行，419通过、1项虹软授权专项按条件跳过；包含既有并发审批用例新增49次重复执行。日志 `redis-final-verify.log`，Surefire XML保留于backend/target。 |
| 真实MySQL、Redis、双JVM | 10组检查全部通过，`artifacts/c-redis-1d296a12fab2/result.json`。跨实例登录/刷新/CSRF/会话固定防护；草稿经Java重启后确认及重复确认只有一条申请；Redis内容无密码/散列/持久对象；旧副本不能撤销取消；取消与确认并发只有一方成功；Redis停止503、AOF重启恢复；命名空间隔离和真实过期；角色/停用/公司/密码变更撤销；退出全实例失效；错误Redis密码启动失败。 |
| Redis模式全部既有浏览器回归 | 49项通过，`artifacts/c-redis-15a687418b15/result.json`及browser.log。独立H2演示库、真实Redis、Chromium1194；该范围与上行真实MySQL双JVM测试分别记录。 |
| 启动器边界 | `scripts/test_c_runtime.py` 8项通过，包含只让Java继承凭据的边界。 |
| 前端 | npm run format、npm run build通过；保留既有报表分包大小提示。未改变前端产品流程。 |
| 配置与脚本 | Python编译、YAML解析、redis-start.sh的sh -n及git diff --check通过。Docker在本机不可用，镜像和Linux独立MySQL组合由新增CI实际验证，结果以PR最新提交检查为准。 |

验收脚本每次拥有独立实例、随机密码与新目录；退出删除临时密码配置、关闭自身进程，保留合成数据库、AOF和日志供本地复查。CI只上传result.json和日志，不上传密码配置或数据库/AOF。

## 保留的失败与修正证据

首轮过期断言直接要求Redis TTL不大于配置3秒，在WSL时钟比Windows慢约0.89秒时观察到TTL4秒而失败（`artifacts/c-redis-a3c2cd936eaf/result.json`）。现同时验证缓存的空闲间隔确为3秒、实测Redis TIME时钟偏差在5秒以内、TTL符合该偏差，并等待实际键消失和HTTP401。通过记录中storedIntervalSeconds=3、observedTtl=4、redisClockSkewSeconds=-0.894。没有延长应用Session配置。

首轮Redis浏览器回归48通过、1失败（`artifacts/c-redis-930def6917f2`）。Playwright请求轨迹显示用例点击退出后立即goto('/login')，中断了获取CSRF的请求，退出POST尚未发送；下一次登录恢复了旧身份。两处切换账号现在先等待退出跳转并断言/api/auth/session为401，再进入登录步骤。保留全部原断言并增加4行等待/401检查；最终49项通过。

## 未据此宣称完成

Redis与MySQL没有分布式事务。进程若在确认过程中退出，处理中标记可能保留至原10分钟有效期；用户应先查看已有申请，不能自动另建申请重试。提交成功后Redis确认写入失败不会释放标记或伪称数据库回滚。

本阶段不包含Redis集群/Sentinel、跨版本任意混合滚动升级、TLS网络实跑、断电/磁盘故障及备份恢复。基本Compose配置校验与Redis镜像测试也不等于完整生产环境部署。Nacos/OpenFeign为C3，部署/备份恢复/Kubernetes/云效为后续C4；A的正式业务库、虹软授权及手机现场仍由共同验收覆盖。
