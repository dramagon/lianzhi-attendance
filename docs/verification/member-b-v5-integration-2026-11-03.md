# B：正式审批整合与V5存储 · 2026-11-03

## 下一步任务与交付

读取交接并获取远端后，发现A已在`codex/member-a-approval-workflow`交付`182d248`，包含正式审批V4与打卡微秒修复V4.1。本轮将该分支整合到B，保留B的打卡恢复、人脸业务与手机交互，不合并main，也不覆盖A/C远端分支。

- 保留A真实申请/审批/审计、READ_COMMITTED联合事务、权限和幂等规则，接通B考勤调整与来源查询。README一处冲突按实际已交付能力合并。
- 正式发布B `V5__face.sql`，默认顺序1→2→3→4→4.1→5；移除候选副本。存储与识别处理分开启用：无SDK时可迁移空表，SDK处理继续关闭。人脸时间列为TIMESTAMP(6)，审计增加员工/版本索引。
- FaceService检查4/4.1/5成功历史。FaceWorkflowTest直接使用正式迁移，不再读取候选目录；运行JAR不包含测试替身。
- 新增4.1→5升级测试，保留原员工、原始打卡及微秒、已批准申请和审计，重复迁移无副作用。V1/V2/V3与A已发布V4/V4.1均未改写。
- MySQL脚本纳入A的14项真实审批流程测试；`--face-workflow`在第二个隔离库运行14项人脸安全业务测试。旧`--face-candidate`保留为兼容别名，报告字段改为正式迁移语义。

## 联调发现的实际问题

首次真实MySQL回归44项中4项失败、3项错误。审批DATETIME写入09:00，UTC主机通过`getTimestamp().toLocalDateTime()`读取时变成01:00，使请求幂等、请假口径、补卡班次和加班重叠判断错误。

将A `ApprovalStore.time`改为JDBC4.2 `getObject(name,LocalDateTime.class)`，保留业务墙上时间，不经主机时区转换。原断言未放宽，服务器时区未改动；随后相同MySQL测试全部通过。此修复不自动重写已经保存的历史申请或调整，若旧环境曾生成错误业务数据，仍须按审计来源核实。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| 干净构建与最终后端verify | 整合版clean verify通过；DATETIME修正后verify 135项通过，0失败/错误/跳过，JAR通过 |
| 前端format/build | 通过 |
| 最终全新内存库浏览器回归 | 33项通过，0失败/跳过，约1.3分钟；含真实申请→审批→B日考勤及原扫码打卡/断网恢复 |
| MySQL8.4.13基础/审批 | 44项通过，含A审批14项、B业务/并发30项 |
| MySQL重启 | 1项新JVM检查通过；原始事件/排班/调整指纹一致 |
| MySQL第二人脸测试库 | 14项通过，明确使用TEST_ONLY_SIMULATOR；两个库均迁移1/2/3/4/4.1/5 |
| 可信HTTPS自检 | CA链、主机名、Secure Cookie、CSRF、回执、设备路由及face_storage_ready通过 |
| JAR核对 | 含6个正式迁移文件，不含Simulator/FaceWorkflowTest |

MySQL产物位于Git忽略目录`artifacts/mysql-b-2dace40657c6/result.json`，容器与临时凭据已清理。HTTPS产物为`artifacts/mobile-v5-https-20261103/readiness.json`。均使用隔离测试库，没有重置用户业务数据库或修改系统证书信任。

## 仍未完成

实际虹软适配器、官方Windows SDK/授权、Windows执行与真实手机现场验收仍未完成；用户此前确认Windows且尚无SDK/授权。仅测试业务替身不会使实际检测、比对、活体标为完成。正常未配置状态为storageAvailable=true，available/detection/comparison/liveness=false。

C的报表/AI与A后续地图分支没有在本轮额外整合；最终main整合仍由A负责。下一项真实识别工作需要官方SDK包、确定版本和对应设备授权，不能用通用图像库或恒真实现代替。
