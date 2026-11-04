# C 第六阶段：报表与CSV导出

实际执行日期2026-11-04，按工作阶段顺延。整合B `5c21217`（含正式V5、审批DATETIME跨时区修复和课堂包），保留既有A地图及C第五阶段，不合并main。真实模型、OCR、转写仍未配置；本阶段不启用V6。

## 真实接口

ADMIN/HR可访问，EMPLOYEE拒绝；Session/CSRF体系保持不变。

- `GET /api/reports/attendance?from=YYYY-MM-DD&to=YYYY-MM-DD&companyId=&departmentId=&userId=&workplaceId=&page=1&size=20`
- `GET /api/reports/attendance/export`使用相同筛选参数，返回`text/csv;charset=UTF-8`、附件`attendance.csv`，不使用ApiResponse包装。

日期必填、最多94个业务日、年份1000～9999；分页沿用1起始、size≤100。空ID不传。HR限定当前所属公司，ADMIN可指定公司；部门/员工/场所逐项复用A服务校验，冲突组合拒绝。只选择当前启用且已分配公司的账号，员工归属/部门按**当前组织**，不是历史组织快照。员工调动后历史排班场所与当前公司冲突时整次报表返回409，避免泄露另一公司的场所。

扫描员工列表最多1000，入选最多100人；超限400，不静默截断。指定员工时可绕过列表扫描但仍鉴权、核对公司部门和启用状态。最大9400条排班日记录；同步限额报表，不新增V7任务表。

## 统计定义

所有日期为Asia/Shanghai。每条日记录来自`AttendanceDays.list`；C不重新判定迟到、早退、缺勤或工时。`AttendanceSchedules`仅提供班次结束时间（含跨夜），结束前单列未结束，不进入出勤率分母。

- `scheduledDays`：筛选匹配的员工排班人天，包含休息/请假/未结束；无正式排班不生成缺勤行。
- `pendingDays`：尚未到班次结束时间或B仍返回PENDING。即使提前签到签退后B已给NORMAL/异常，未结束班次仍不进入出勤率分母。
- `expectedDays`：已结束且不是REST_DAY、ON_LEAVE、PENDING的人天。
- `attendedDays`：上述分母内B状态为NORMAL/LATE/EARLY/LATE_AND_EARLY的人天；代表完整打卡出勤，含迟到/早退，不代表全勤率。
- `attendanceRate`：attendedDays/expectedDays×100，保留2位；分母为0返回null，UI显示“不适用”。全日请假排除分母，部分请假保留。
- `lateDays` / `earlyDays`：分母范围内B对应分钟数大于0；同日可分别计数。ABSENT/MISSING_PUNCH直接计数，未结束不提前判断。
- 工时/请假/加班分钟直接累加B实时字段，包括仍在进行但已有完整打卡的日记录；不能把实时分钟称为已结算最终值。

`trend`按每个查询日补齐空汇总，`workplaces`按办公场所登记坐标汇总排班、有签到或签退人天及工时。前端按坐标系分组画相对分布，圆大小表示有打卡人天；**不是地图瓦片/连续地理热力图、实时员工定位或人流预测**。没有跨坐标系混绘或伪坐标转换。各表有具体坐标和数字，趋势有可展开日数据。

`records`标准分页，包含userId、工号/姓名、当前公司部门、日期、场所、B状态与打卡、分钟、approvalIds。用B `GET /api/attendance/days`按该userId/日期复核，用A `GET /api/approvals/{id}`回查审批审计；原始打卡与审批调整仍分别保留，不把补卡当原始事件。

## 一致性与导出

查询与导出分别在REPEATABLE_READ只读事务里读取；各自结果内部一致，导出时重新读取，期间打卡/审批变化可能与先前页面不同，不是固定快照下载。导出使用同一身份/筛选/限额，不只导出当前页。返回UTF-8 BOM、CRLF、所有字段双引号并转义内嵌引号；对公式标记`= + - @`（含前导空白）及控制字符前加单引号，避免表格软件公式解释。响应禁缓存、nosniff。

前端入口`/reports`仅ADMIN/HR可见，使用已有organization:manage导航权限；服务端另行判断角色和公司范围。修改条件清除旧结果，旧请求不能覆盖新条件；筛选建议只列已有目录前100项并明确提示，仍可输入ID。后台失败显示明确错误，不保留旧数据冒充新范围。

## 本阶段限制

没有XLSX异步任务、ECharts、连续地图热力、预测或历史人员归属快照；Docker与同源代理已提供[配置及验收边界](../../deploy/README.md)，尚待有Docker环境实跑。B正式V5已整合，C后续V6可在团队按序合并后推进，不把仍关闭的知识库视为完成。
