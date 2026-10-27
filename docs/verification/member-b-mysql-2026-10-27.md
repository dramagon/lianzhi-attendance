# B：真实MySQL验证 · 2026-10-27

## 结果

本次实际运行MySQL **8.4.13**（Oracle MySQL官方8.4容器，不是MariaDB或H2兼容模式）。数据库和账号由测试脚本创建在独立、仅回环端口的容器内；没有连接用户现有业务库。

- 真实MySQL业务测试：**23项通过，0失败/错误/跳过**。
  - AttendanceWorkflowTest 11项：真实组织/绑定/正式排班、打卡、日口径、补卡、请假、加班及权限。
  - AttendanceApiTest 7项：认证、CSRF、角色范围、格式错误及未接入SDK行为。
  - AttendanceMysqlIT 5项：不同请求并发只落一条、相同请求并发返回同一事件、相同审批并发幂等、调用方写入与B调整整体回滚、已提交调整可追溯。
- MySQL容器真实重启后，在**新的JVM**启动Spring上下文：**1项通过**。Flyway再次校验V1/V2/V3，已提交的事件、正式班次和审批调整均保留。
- 重启前后的完整事件/班次/调整查询结果SHA-256一致。最终测试库保留2条事件、5条班次、2条调整；测试结束按脚本约定清理该独立容器和匿名数据卷，日志/结果保留。
- 数据库类型在用例中断言为MySQL，禁止将H2回退当作通过。重启前后SQL指纹与新JVM断言共同验证，不只检查进程或端口。

最终报告目录：`artifacts/mysql-b-cf4ca7298281/`（Git忽略）。其中mysql-tests-results仅包含本次执行的三个目标类，mysql-restart-results仅包含重启后的目标类；result.json包含版本、迁移、数量和重启指纹，未包含密码。容器临时凭据文件已删除。

固定镜像：

```text
mysql:8.4@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69
```

## 找到并修复的问题

原实现依赖员工行锁串行化打卡，但MySQL默认REPEATABLE READ的读快照可能在取行锁前已建立。相同请求并发时只写入一条记录，但等待者可能看不到已提交事件，返回409而非原事件。

打卡、排班保存及审批调整的写事务现显式使用READ_COMMITTED，使取锁后的业务读取看到前一个事务提交的事实。日考勤查询仍使用REPEATABLE_READ作一致读。修正前的MySQL并发测试真实失败，修正后同请求及同审批并发均通过，没有放松幂等断言。

**A对接要求：**如果A审批已有外层事务，B的REQUIRED事务会加入它，内部注解不会替换外层隔离级别。因此A真实审批事务也应明确READ_COMMITTED、同一数据源，并在同一事务内做状态CAS/审计及B的validate/apply；不能拆成异步或另一个独立事务。本轮回滚测试模拟调用方后续审计失败，验证调用方写入和B调整同时回滚；不等于A的V4审批页面已完成。

测试脚本还重新发现容器重启后的动态端口，不假设随机端口不变；每次保留本次目标类的XML，防止旧结果混入。

## 复跑

前提：Docker守护进程可用、Python3、项目要求的JDK和Maven Wrapper可用。无需安装宿主机MySQL、无需用户提供数据库密码。

```bash
python scripts/test-attendance-mysql.py
```

云环境需要现有代理设置和缓存时：

```bash
JAVA_HOME=/workspace/.local/jdk/usr/lib/jvm/java-21-openjdk-amd64 \
MAVEN_USER_HOME=/workspace/.cache/maven \
python scripts/test-attendance-mysql.py \
  --maven-settings /workspace/.cache/maven/settings.xml \
  --maven-repo /workspace/.cache/maven/repository
```

脚本随机生成仅本次使用的账号密码，临时文件权限限制为当前用户，端口仅绑定127.0.0.1；不读取已有MySQL配置或重置用户密码。无论用例成功或失败，只清理自己创建的容器及数据卷。日志在artifacts内，不提交密码或测试数据。

`*MysqlIT`、`*MysqlRestartIT`为显式选择的集成测试，不会混入默认H2回归；`LIANZHI_ATTENDANCE_TEST_URL`由隔离脚本设置。不要把该变量指向有价值的业务库：这些是会写入合成数据的测试。

## 验证边界

本轮为Linux、Java21按release17编译及真实MySQL8.4.13验证。未宣称Windows/MySQL8.0、物理手机摄像头/GPS、HTTPS部署或虹软SDK已通过。SDK授权、A真实V4审批及真机仍按交接文档推进。前端未修改，因此没有把上轮18项浏览器结果冒充本轮MySQL浏览器验证。
