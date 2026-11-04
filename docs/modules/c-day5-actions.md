# C 第五阶段：GitHub整合、授权汇总与确认提交

日期2026-11-01。按已完成进度进入第五工作阶段，原排期10/30已顺延，不将第六/七阶段标为完成。共同依据仍为团队契约、C任务及实训PDF；当天全部验证结束后统一提交一次。

## GitHub核对与整合

main仍67969ac，PR #1～#8均未合并。A最新d6ce1b7通过PR #6～#8继承C fa82d78，包含三人联调、首页入口和地图/定位预览确认修复；B最新0b9e99c包含打卡回执恢复、人脸安全流程，和A分支分叉于e3eafe6。新分支codex/member-c-day5-actions先快进A，再no-commit合入B，保留两方历史。README一处新旧状态冲突按真实能力合并，其他文件自动合并；没有覆盖他人分支或合并main。

原生拉取最初出现unresolved deltas，使用git fetch --refetch完整获取后成功，原对象/工作区保留。B V5仍是docs候选，SDK适配器未实现；人脸默认关闭，不将安全流程测试替身当真实SDK。常规Flyway仍为V1/V2/V3/V4/V4.1，不激活V5/V6。

## 确认写入协议

所有路径前缀`/api/assistant`，使用现有Session、CSRF、ApiResponse和A服务。调用者身份、公司和auth_version每次从服务器取得，不采信模型/浏览器的userId、角色或公司声明。

| 方法/路径 | 输入与行为 |
| --- | --- |
| POST `/approval-drafts` | `{intentKey,draft}`；intentKey为标准小写UUID，同意图重试保持不变；draft沿用A的LEAVE/OVERTIME/MISSING_PUNCH。A.preview校验并规范化，无申请/审计写入。返回服务器confirmationId、expiresAt及完整preview。 |
| POST `/approval-drafts/confirm` | 仅`{confirmationId,confirmed:true}`；读取本会话服务器草稿，不采信请求附加的新draft。检查当前身份/角色/公司/版本、未取消及未过期；调用A正常服务重新校验与create，返回真实Entry。 |
| POST `/approval-drafts/{id}/cancel` | 取消尚未提交的草稿；重复取消安全。已提交时409，不能以“取消草稿”撤回真实申请，须进入A正常撤回流程。 |

准备阶段不落申请；确认前必须展示申请人、类型、时间、事由及补卡方向/场所，并由用户勾选后点击提交。自然预览分钟不等于有效工时或审批通过。真实模型当前未配置，本页手工准备草稿，不冒充AI自动生成；未来模型最多建议草稿，confirm不得导出为模型工具，确认ID不交给模型自动执行。

服务端状态保存在当前登录HTTP会话，10分钟有效，每会话最多10条（含已取消/已提交，过期后清理）。同intentKey、同内容的未提交重试返回同confirmationId；同意图不同内容、已使用或取消则409。UI预览后锁定编辑，取消成功才可修改；内容变化后使用新intentKey。

确认时在同一会话状态锁内使用独立READ_COMMITTED事务，A.create加入该事务、取得员工锁；写入后再次检查当前身份，发生变化则申请与审计一起回滚。事务提交成功才标记已提交。同confirmationId重复/并发确认使用同一个A requestKey（intentKey），由A数据库幂等保证一条申请、一条提交审计。失败不标记成功，保留可取消/重试状态。

草稿只在本实例HTTP会话内，浏览器刷新可能丢失前端确认卡，服务重启/会话失效不可继续确认；遇到超时或结果不明须先查询“我的申请”，不自动创建新意图重发。真实申请与幂等结果持久化在A V4，C不新增V6/V7或伪造会话持久化。多实例需要共享/粘滞Session后另验，不能把本实现当分布式确认协议。Session草稿包含本人事由，依现有会话访问控制保护，不写日志或Git。

`GET /status`的writeAvailable现表示A真实申请依赖可用且具有上述确认通道，不代表模型可直接写入、用户必定满足公司/场所约束或申请必定获批。此前第四天writeAvailable=false为历史状态，相关C断言随本日新增能力调整。

## 授权统计

`GET /api/assistant/attendance/summary?from=&to=&companyId=&departmentId=`仅ADMIN/HR；复用A身份、公司/部门与员工范围，再调用B `AttendanceDays.list`。员工即使自报公司也不能读取公司汇总，HR不能跨公司。可选部门必须实际存在且属于授权公司；ADMIN可选择全局或公司。

只汇总**当前启用且已分配公司的账号**，不代表历史离职人员报表。日期最多94天；公司范围最多扫描1000个账号，选中最多100名员工，超限明确400，不截断后报完整结果。返回employeeCount、scheduledDays、pendingDays、workMinutes、leaveMinutes、overtimeMinutes、statuses、sourceUserIds与来源AttendanceDays.list。数值直接相加B字段，工时/请假/加班分别呈现，不另造日状态；PENDING单独计数，不估算缺勤率/出勤率。无排班为空统计，不解释为正常出勤。查询在一致读事务内，无结果缓存。

页面管理角色可按授权部门选择汇总；部门选项当前最多100个，后端汇总仍按选定范围完整计算。员工页面不展示管理按钮，服务端继续拒绝直接绕过。尚未实现第六阶段的完整报表、图表、导出与日期/员工高级筛选。

## 验收与剩余条件

验证预览不写入、明确确认、重复/并发、篡改内容/身份/会话、取消、过期、身份变更回滚、场所解绑、CSRF和资源上限；汇总与B同字段一致、部门/公司越权、PENDING与无排班、员工限额。保留A/B/C原断言，新B人脸流程只在隔离测试库应用候选迁移和明确测试替身。

真实模型/OCR/转写/embedding仍未配置，制度问答与知识上传仍关闭；V5/V6、真实SDK/真机、MySQL联合确认并发、模型自动建议、完整报表/部署和第七阶段交付仍待验收。具体测试与PR/SHA见[第五阶段验证](../verification/member-c-day5.md)。
