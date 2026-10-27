# B模块：排班、考勤与人脸接入边界

2026-10-27：B分支整合A组织/场所/审批前置分支`4216eb8`，不合并main。V1/V2保持原样，新增正式V3；此前只含事件表的候选V3已移除。正式排班、真实挑战二维码、WGS84定位打卡、本人原始记录、日考勤与审批调整服务已实现。人脸SDK与A的真实审批保存尚待配合，不宣称完整人脸或整条审批业务已完成。

## 使用流程

1. 管理员先在A的组织与场所中建立公司、员工及有效场所绑定；历史演示账号若没有公司，须明确设置归属，系统不猜测归属。
2. 考勤工作台→「正式排班与日考勤」：选择员工、日期、有效WGS84场所，设置班次、休息、工作日及休息日标记，保存。下班早于上班为跨天，归班次开始日。只能维护当日/未来、尚无打卡或审批调整的排班，相邻班次不能重叠。
3. ADMIN或本公司HR从考勤工作台的场所管理生成60秒二维码。二维码只表达场所和短时挑战，不包含员工身份。员工用摄像头扫码或识别本机图片，也可粘贴令牌；图片仅本机处理，不上传。识别成功/关闭/切页释放扫码摄像头。
4. 员工主动获取真实WGS84定位，选择上班/下班并确认，时间由服务器生成。正式班次、场所绑定、公司范围、令牌签名/有效期、距离/精度、顺序和重复提交由后端复核。成功记录持久化在数据库，页面不会用模拟数据兜底。
5. 「考勤记录」查看原始事件；「正式排班与日考勤」查看统一日结果及审批来源。规则试算仍无保存副作用。

摄像头/定位在HTTPS或本机安全上下文使用，需要设备授权；云浏览器仿真定位不等于真实手机验收。人脸页仅本机摄像头预览，检测/比对/活体当前均未接入，登记/验证返回503。A头像不是人脸凭据。

## 接口与权限

沿用Session、CSRF与ApiResponse。POST需先GET `/api/auth/csrf`，使用返回的头名/token。本人ID只取认证上下文；管理查询和排班复用A的DataScope、EmployeeService和WorkplaceService，HR仅本公司。日期范围from/to最多94天。

| 方法/路径（前缀/api） | 行为 |
| --- | --- |
| GET `/attendance/status` | available、rulesAvailable、directoryAvailable、storageAvailable、blockers、timeZone、serverTime；available表示服务可用，不代表某员工已有排班 |
| POST `/attendance/rules/preview` | 无副作用规则试算；所有启用身份 |
| POST `/attendance/schedules` | ADMIN/HR保存本人范围内员工排班，Schedule请求见下；其他员工403 |
| GET `/attendance/schedules?userId=&from=&to=` | 管理角色查询范围内员工班次 |
| GET `/attendance/me/schedules?from=&to=` | 本人班次 |
| GET `/attendance/me/workplaces` | 本人有效绑定中可用于当前打卡的WGS84场所 |
| GET `/attendance/managed/workplaces` | ADMIN/HR管理范围内启用且WGS84的场所（当前选择器最多100个） |
| POST `/attendance/workplaces/{id}/challenge` | ADMIN/HR且有场所公司管理范围；返回token、expiresAt |
| POST `/attendance/me/punches` | 本人真实打卡；无排班503、无绑定403、输入/范围错误400、重复409 |
| GET `/attendance/me/records?from=&to=&page=1&size=20` | 本人原始事件，items/total/page/size；size 1–100，page 1–100000 |
| GET `/attendance/me/days?from=&to=` | 本人按排班日排序的日记录数组；没有排班不推断缺勤 |
| GET `/attendance/days?userId=&from=&to=` | ADMIN/HR查询范围内员工同口径日记录数组 |
| GET `/face/status` | SDK能力均false；不把设备预览当识别成功 |
| POST `/face/enrollment`、`/face/verification` | 当前503，未生成登记或验证凭据 |

Schedule请求：

```json
{"userId":3,"businessDate":"2026-10-27","workplaceId":1,"rule":{"start":"09:00","end":"18:00","breakStart":"12:00","breakEnd":"13:00","graceMinutes":5,"workdays":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]},"holiday":false}
```

规则试算请求使用`{date,rule,checkIn,checkOut,holiday,finalized}`；checkIn/checkOut为北京时间完整本地日期时间或null，跨天签退须填次日。返回status、lateMinutes、earlyMinutes、workMinutes、breakMinutes、overnight、notes。`09:12→18:00`、09–18班次、12–13休息时，迟到12分、有效工时468分。

打卡请求：

```json
{"type":"IN","token":"签名挑战","requestId":"客户端UUID","location":{"latitude":31.2,"longitude":121.5,"coordinateSystem":"WGS84"},"accuracyMeters":10}
```

当前场所半径支持A的10～10000米范围（底层校验1～10000）；定位精度须为正数且不大于min(半径,100米)，页面定位60秒内有效。Haversine计算距离。GCJ02/BD09场所不会当WGS84打卡：管理方需提供正确WGS84坐标，后续若接转换需另行验证，不静默换算或改写A场所。

## 排班与统一日口径

- 一名员工每个业务日一个班次。跨天归开始日；打卡窗口为班次前后4小时，重叠窗口优先实际班次内、其次距边界最近的班次。过期/未安排时拒绝，不填演示班次。
- 工作时区Asia/Shanghai。宽限内不计迟到，超过按相对开始的总延迟向上取整分钟；早退同样向上取整。班次内实际出勤与休息重叠扣除后向下取整；不把提前到场、晚走自动计入加班。
- 完整班次未结束缺事件时PENDING，结束后全缺ABSENT、单缺MISSING_PUNCH。休息日REST_DAY；获批全班请假ON_LEAVE。
- 日记录是**可复现的实时投影**：V3持久化正式班次、不可变原始事件和独立审批调整；日接口在一致读事务内重算，不另存易过期的重复统计行。补卡、请假、加班均保留approvalIds来源，C直接复用日服务，不另造口径。
- 请假只抵扣获批时段的迟到/早退，工时不重复计入请假覆盖时间；请假分钟数排除班内休息。加班只计算获批时段与实际签到签退的重叠，不把申请时长当实际加班。当前加班限定单一已排班业务日、班次前后4小时窗口；长时/多日加班需拆分申请或后续扩展。
- 无真实审批记录时不生成调整。补卡不会修改/伪造原始事件，已批准签到可支撑之后真实签退；已完成方向不能再次原始打卡或补卡。

## 安全与持久化

V3依次在V1/V2后创建attendance_event、attendance_schedule、attendance_adjustment，引用真实sys_user/workplace。没有应用V4/V5。

HMAC-SHA256挑战绑定场所、过期秒数和随机nonce，恒定时间验签。单实例进程密钥重启即轮换，旧二维码失效；多实例共享密钥/轮换方案尚未实现，不能直接扩成多实例。每个员工独立防重，共享场所二维码允许不同员工使用。数据库唯一约束为(userId,requestId)、(userId,businessDate,type)、(userId,nonce)。事务锁定当前员工，串行处理打卡、排班和调整；重复请求在有效挑战窗口内返回原事件，过期后应先查询记录核实。

EventView仅返回id/workplaceId/businessDate/type/occurredAt/distanceMeters，不返回原始坐标、nonce或请求编号。定位是浏览器提供的设备信息，不能宣称已防GPS作弊。真实MySQL/Windows与真机需分别验证。

## A/B审批服务契约

B已实现A定义的`ApprovalAttendancePort`，`available()`表示V3可用。`validate(Authentication,Adjustment)`复核管理角色、调用者/approverId一致、不自审、申请人/公司匹配、真实排班/场所、顺序、重叠和补卡方向。`apply`再次检查，并以approvalId幂等追加attendance_adjustment；同ID不同内容冲突，原始事件不变。以同一员工锁防并发，计算失败事务回滚。

这是**仅供可信服务调用的Java接口，没有HTTP“直接审批调整”接口**。A必须在真实申请审批事务内：锁定待审批申请、再次鉴权并CAS转换状态，再调用B的validate/apply；审批状态、审计、B调整应同一数据源同一事务，失败整体回滚。不能让浏览器/模型自行构造Adjustment，也不能把A的preview当审批成功。B不写A的申请表、不激活A的V4。

## 需要其他模块协助

见[本轮交接与后续事项](../team/member-b-handoff-2026-10-27.md)。A接真实V4与审批事务，C接带身份范围的日服务。虹软SDK版本/系统平台/授权和V5需在V4顺序明确后继续；当前无官方SDK授权，不能以恒真逻辑代替识别。

## 验证

后端`sh ./mvnw verify`；前端`npm ci`、`npm run format`、`npm run build`，启动独立演示库后`npm run test:e2e`。云环境外部Playwright配置选择系统Chromium，开发机按README安装Playwright Chromium。

浏览器联调使用隔离临时H2，不修改用户已有数据，定位使用浏览器仿真；二维码图片由真实签名令牌生成并使用jsQR真实解码，没有用假的“识别成功”替代。具体结果见交接记录。

### MySQL后续验证

MySQL8.4.13上23项业务/并发/回滚测试及1项数据库重启后新JVM检查已通过；见[验证记录](../verification/member-b-mysql-2026-10-27.md)。写事务显式READ_COMMITTED，日查询仍为REPEATABLE_READ。A若提供外层审批事务，必须同时明确READ_COMMITTED，内部REQUIRED注解不能改变调用方隔离级别。
