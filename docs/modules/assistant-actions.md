# 业务助手与申请确认

## 确认写入协议

所有路径前缀`/api/assistant`，使用现有Session、CSRF、ApiResponse和组织与审批服务。调用者身份、公司和auth_version每次从服务器取得，不采信模型/浏览器的userId、角色或公司声明。

| 方法/路径 | 输入与行为 |
| --- | --- |
| POST `/approval-drafts` | `{intentKey,draft}`；intentKey为标准小写UUID，同意图重试保持不变；draft沿用审批的LEAVE/OVERTIME/MISSING_PUNCH。审批preview校验并规范化，无申请/审计写入。返回服务器confirmationId、expiresAt及完整preview。 |
| POST `/approval-drafts/confirm` | 仅`{confirmationId,confirmed:true}`；读取本会话服务器草稿，不采信请求附加的新draft。检查当前身份/角色/公司/版本、未取消及未过期；调用A正常服务重新校验与create，返回真实Entry。 |
| POST `/approval-drafts/{id}/cancel` | 取消尚未提交的草稿；重复取消安全。已提交时409，不能以“取消草稿”撤回真实申请，须进入正常撤回流程。 |

准备阶段不落申请；确认前必须展示申请人、类型、时间、事由及补卡方向/场所，并由用户勾选后点击提交。自然预览分钟不等于有效工时或审批通过。模型仅建议草稿，confirm不得作为模型自动执行工具；必须由用户核对并确认。

服务端状态保存在当前登录HTTP会话，10分钟有效，每会话最多10条（含已取消/已提交，过期后清理）。同intentKey、同内容的未提交重试返回同confirmationId；同意图不同内容、已使用或取消则409。UI预览后锁定编辑，取消成功才可修改；内容变化后使用新intentKey。

确认时在同一会话状态锁内使用独立READ_COMMITTED事务，审批create加入该事务、取得员工锁；写入后再次检查当前身份，发生变化则申请与审计一起回滚。事务提交成功才标记已提交。同confirmationId重复/并发确认使用同一个申请requestKey（intentKey），由A数据库幂等保证一条申请、一条提交审计。失败不标记成功，保留可取消/重试状态。

默认草稿保存在本实例HTTP会话内；启用[Redis共享会话](redis-session.md)后可跨实例访问。超时或结果不明时先查询本人申请，不自动创建新意图重发。持久化申请由数据库幂等约束保护。

`GET /status`的writeAvailable现表示真实申请依赖可用且具有上述确认通道，不代表模型可直接写入、用户必定满足公司/场所约束或申请必定获批。

## 授权统计

`GET /api/assistant/attendance/summary?from=&to=&companyId=&departmentId=`仅ADMIN/HR；复用A身份、公司/部门与员工范围，再调用 `AttendanceDays.list`。员工即使自报公司也不能读取公司汇总，HR不能跨公司。可选部门必须实际存在且属于授权公司；ADMIN可选择全局或公司。

只汇总**当前启用且已分配公司的账号**，不代表历史离职人员报表。日期最多94天；公司范围最多扫描1000个账号，选中最多100名员工，超限明确400，不截断后报完整结果。返回employeeCount、scheduledDays、pendingDays、workMinutes、leaveMinutes、overtimeMinutes、statuses、sourceUserIds与来源AttendanceDays.list。数值直接相加考勤字段，工时/请假/加班分别呈现，不另造日状态；PENDING单独计数，不估算缺勤率/出勤率。无排班为空统计，不解释为正常出勤。查询在一致读事务内，无结果缓存。

页面管理角色可按授权部门选择汇总；部门选项当前最多100个，后端汇总仍按选定范围完整计算。员工页面不展示管理按钮，服务端继续拒绝直接绕过。完整报表及导出见[统计说明](reports.md)。
