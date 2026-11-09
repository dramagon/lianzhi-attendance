# B模块：排班、考勤与人脸接入边界

## 2026-11-07 坐标衔接更新

WGS84场所直接参与距离校验，GCJ02场所由服务端迭代逆换算到WGS84。档案原坐标及坐标系保留。排班下拉和考勤授权目录支持两种场所，目录中的point统一WGS84，员工上报仍只接受WGS84。BD09拒绝用于排班。原公司范围、员工绑定、正式班次、二维码、精度和半径校验继续执行。

GCJ02范围限经度72.024～137.8147、纬度0.8493～55.8071，边缘和范围外拒绝换算。迭代最多12次，数值残差小于1e-8度才返回。该阈值表示公式收敛程度，实际地图/GPS精度需现场核对，不能据此保证实地定位误差。原WGS84数据无需迁移。

公式参考[eviltransform](https://github.com/googollee/eviltransform)，BSD许可随后端JAR的META-INF/licenses/eviltransform.txt分发。北京参考点数值误差检查、保留原坐标、GPS打卡持久化、精度及围栏拒绝检查在本轮加入回归。此前“仅WGS84”的说明为历史限制，以本节为准。

2026-10-27：B分支整合A组织/场所/审批前置分支`4216eb8`，不合并main。V1/V2保持原样，新增正式V3；此前只含事件表的候选V3已移除。正式排班、真实挑战二维码、WGS84定位打卡、本人原始记录、日考勤与审批调整服务已实现。该段为初次交付记录。2026-11-03已接入A正式审批182d248及V4/V4.1，并正式发布V5存储；实际SDK仍未接入，识别处理保持关闭。

## 使用流程

1. 管理员先在A的组织与场所中建立公司、员工及有效场所绑定；历史演示账号若没有公司，须明确设置归属，系统不猜测归属。
2. 考勤工作台→「正式排班与日考勤」：选择员工、日期、有效WGS84场所，设置班次、休息、工作日及休息日标记，保存。下班早于上班为跨天，归班次开始日。只能维护当日/未来、尚无打卡或审批调整的排班，相邻班次不能重叠。
3. ADMIN或本公司HR从考勤工作台的场所管理生成60秒二维码。二维码只表达场所和短时挑战，不包含员工身份。员工用摄像头扫码或识别本机图片，也可粘贴令牌；图片仅本机处理，不上传。识别成功/关闭/切页释放扫码摄像头。
4. 员工主动获取真实WGS84定位，选择上班/下班并确认，时间由服务器生成。正式班次、场所绑定、公司范围、令牌签名/有效期、距离/精度、顺序和重复提交由后端复核。成功记录持久化在数据库，页面不会用模拟数据兜底。
5. 「考勤记录」查看原始事件；「正式排班与日考勤」查看统一日结果及审批来源。规则试算仍无保存副作用。

摄像头/定位在HTTPS或本机安全上下文使用，需要设备授权；云浏览器仿真定位不等于真实手机验收。人脸页仅本机摄像头预览，检测/比对/活体当前均未接入，登记/验证返回503。A头像不是人脸凭据。

## 接口与权限

课堂展示包额外提供`GET /api/attendance/presentation`，仅`classroom` profile和`lianzhi.demo=true`同时启用时存在，仍要求登录；返回classroom、instance、message。正常应用不存在该接口。展示台准备档案、签发二维码、打卡与审批全部调用现有受认证/CSRF/角色保护的业务API，没有额外绕过权限的写接口。使用说明见[课堂展示包](../../tools/classroom/README.md)。

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
| GET `/attendance/me/punches/requests/{requestId}` | 登录后只读查询本人的请求回执；UUID无效400；已落库返回`{found:true,event:EventView}`，暂未查到返回`{found:false,event:null}` |
| GET `/attendance/me/records?from=&to=&page=1&size=20` | 本人原始事件，items/total/page/size；size 1–100，page 1–100000 |
| GET `/attendance/me/days?from=&to=` | 本人按排班日排序的日记录数组；没有排班不推断缺勤 |
| GET `/attendance/days?userId=&from=&to=` | ADMIN/HR查询范围内员工同口径日记录数组 |
| GET `/face/status` | available、detection/comparison/liveness、provider、message、storageAvailable、enrolled、revision、updatedAt、punchRequired、deletionAvailable；默认未接SDK时关闭 |
| POST `/face/enrollment` | 本人登记；multipart image（JPEG/PNG≤2MB）、consent=true；已有登记409；未就绪503 |
| PUT `/face/enrollment` | 本人更新；multipart image、consent=true、expectedRevision；版本变化409，撤销旧挑战 |
| DELETE `/face/enrollment?expectedRevision=` | 本人删除及撤销挑战；保留原始考勤，SDK/密钥不可用但存储已发布时仍可删除 |
| POST `/face/challenges` | JSON `{token,requestId,type}`；复核本人绑定/正式排班和二维码，返回id/expiresAt（30秒） |
| POST `/face/verification` | multipart challengeId、image、consent=true；实际SDK校验，返回verified、verificationId或null、expiresAt、message；失败最多3次 |

2026-11-08新增可选Windows x64 ArcFace 3.0服务端适配器，接口形状不变。授权及引擎实际初始化成功后，status可报告SDK能力；`available`仍同时取决于存储、稳定加密密钥、已明确校准的身份阈值等条件。初始化成功不代表本人比对或活体已经验收，未校准时继续关闭登记/验证。配置、实际结果及本机摄像头分数工具见[b-face-integration.md](b-face-integration.md)。

Schedule请求：

```json
{"userId":3,"businessDate":"2026-10-27","workplaceId":1,"rule":{"start":"09:00","end":"18:00","breakStart":"12:00","breakEnd":"13:00","graceMinutes":5,"workdays":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]},"holiday":false}
```

规则试算请求使用`{date,rule,checkIn,checkOut,holiday,finalized}`；checkIn/checkOut为北京时间完整本地日期时间或null，跨天签退须填次日。返回status、lateMinutes、earlyMinutes、workMinutes、breakMinutes、overnight、notes。`09:12→18:00`、09–18班次、12–13休息时，迟到12分、有效工时468分。

打卡请求：

```json
{"type":"IN","token":"签名挑战","requestId":"客户端UUID","location":{"latitude":31.2,"longitude":121.5,"coordinateSystem":"WGS84"},"accuracyMeters":10}
```

启用人脸强制策略时，另传`faceVerificationId`，必须是服务端当前本人、二维码nonce、场所、requestId、方向和登记版本匹配的已验证挑战；不能传`verified:true`代替。最终打卡事务消费一次性凭据，落库失败共同回滚；已落库的相同请求仍按原事件幂等返回。

2026-11-08补充非强制场所的可选识别：服务可用且本人已登记时，打卡页可勾选“本次打卡使用人脸识别”；本次选择后，界面须等待有效验证凭据才允许提交，取消选择会撤销本页旧凭据。后端创建新打卡时，只要`faceVerificationId`非null就必须按同一规则校验并消费，不因场所非强制而忽略。未提交该字段或为null时，仍遵守原二维码/定位及服务端强制策略；不能用客户端选择取消服务端人脸必需要求。成功提示仅在本次使用有效人脸凭据且打卡返回成功时说明已通过人脸验证，不把普通打卡标成人脸成功。没有新增持久化字段或改写原记录。

登记/更新返回`{enrolled,revision,updatedAt}`，不输出特征、加密数据或图像。未通过质量、人数、活体、比对时verification返回`verified:false,verificationId:null`，HTTP200不代表识别通过，调用方必须检查verified严格为true。SDK异常503、挑战过期/错配/耗尽400；同一挑战验证完成后不可重新上传，更新/删除使旧挑战失效。服务端阈值来自实际SDK校准配置，不由客户端提交。

当前场所半径支持A的10～10000米范围（底层校验1～10000）；定位精度须为正数且不大于min(半径,100米)，页面定位60秒内有效。Haversine计算距离。GCJ02/BD09场所不会当WGS84打卡：管理方需提供正确WGS84坐标，后续若接转换需另行验证，不静默换算或改写A场所。

### 响应丢失与网络恢复（2026-11-01）

浏览器在提交前将`{requestId,type}`保存到当前标签页的sessionStorage，以当前登录用户ID分隔；不保存二维码、定位、照片或Cookie。请求超时、连接中断或5xx时保持“结果待核对”，暂停新的提交。恢复联网、重新进入考勤页或刷新时只发起回执GET，不自动重发POST；也可手动点击“核对上次打卡结果”。禁用浏览器存储时仅保留当前页面内存状态，刷新后需查看本人记录核实。

回执查询由认证上下文确定userId，即使管理人员持有其他员工的requestId也查不到该员工记录。查询只读取已存在的原始事件，不要求仍持有有效二维码、当前班次或场所绑定；因此令牌过期、跨业务日后仍可核实历史提交。返回EventView，不输出内部nonce、请求编号和原始位置。

`found:false`只表示查询时尚未看到已提交记录，不证明原请求已经取消。页面提示稍后再查；若选择“重新准备打卡”，需再次确认，并清空二维码、定位及旧请求编号。必须重新扫码和定位，服务端同班次/方向的唯一约束仍生效。此查询不提供离线打卡、自动补签或补发队列。

## 排班与统一日口径

- 一名员工每个业务日一个班次。跨天归开始日；打卡窗口为班次前后4小时，重叠窗口优先实际班次内、其次距边界最近的班次。过期/未安排时拒绝，不填演示班次。
- 工作时区Asia/Shanghai。宽限内不计迟到，超过按相对开始的总延迟向上取整分钟；早退同样向上取整。班次内实际出勤与休息重叠扣除后向下取整；不把提前到场、晚走自动计入加班。
- 完整班次未结束缺事件时PENDING，结束后全缺ABSENT、单缺MISSING_PUNCH。休息日REST_DAY；获批全班请假ON_LEAVE。
- 日记录是**可复现的实时投影**：V3持久化正式班次、不可变原始事件和独立审批调整；日接口在一致读事务内重算，不另存易过期的重复统计行。补卡、请假、加班均保留approvalIds来源，C直接复用日服务，不另造口径。
- 请假只抵扣获批时段的迟到/早退，工时不重复计入请假覆盖时间；请假分钟数排除班内休息。加班只计算获批时段与实际签到签退的重叠，不把申请时长当实际加班。当前加班限定单一已排班业务日、班次前后4小时窗口；长时/多日加班需拆分申请或后续扩展。
- 无真实审批记录时不生成调整。补卡不会修改/伪造原始事件，已批准签到可支撑之后真实签退；已完成方向不能再次原始打卡或补卡。

## 安全与持久化

V3依次在V1/V2后创建attendance_event、attendance_schedule、attendance_adjustment，引用真实sys_user/workplace。2026-11-03默认迁移已包含A V4、V4.1及B V5；V5仅准备存储，不启用SDK。人脸时间列使用微秒精度，审计按员工/版本建立索引。

HMAC-SHA256挑战绑定场所、过期秒数和随机nonce，恒定时间验签。单实例进程密钥重启即轮换，旧二维码失效；多实例共享密钥/轮换方案尚未实现，不能直接扩成多实例。每个员工独立防重，共享场所二维码允许不同员工使用。数据库唯一约束为(userId,requestId)、(userId,businessDate,type)、(userId,nonce)。事务锁定当前员工，串行处理打卡、排班和调整；重复请求在有效挑战窗口内返回原事件，过期后应先查询记录核实。

EventView仅返回id/workplaceId/businessDate/type/occurredAt/distanceMeters，不返回原始坐标、nonce或请求编号。定位是浏览器提供的设备信息，不能宣称已防GPS作弊。真实MySQL/Windows与真机需分别验证。

## A/B审批服务契约

B已实现A定义的`ApprovalAttendancePort`，`available()`表示V3可用。`validate(Authentication,Adjustment)`复核管理角色、调用者/approverId一致、不自审、申请人/公司匹配、真实排班/场所、顺序、重叠和补卡方向。`apply`再次检查，并以approvalId幂等追加attendance_adjustment；同ID不同内容冲突，原始事件不变。以同一员工锁防并发，计算失败事务回滚。

这是**仅供可信服务调用的Java接口，没有HTTP“直接审批调整”接口**。A必须在真实申请审批事务内：锁定待审批申请、再次鉴权并CAS转换状态，再调用B的validate/apply；审批状态、审计、B调整应同一数据源同一事务，失败整体回滚。不能让浏览器/模型自行构造Adjustment，也不能把A的preview当审批成功。当前已接入A正式ApprovalService的create/decide及V4，B不另写一套申请审批。

## 需要其他模块协助

见[本轮交接与后续事项](../team/member-b-handoff-2026-10-27.md)。A正式V4与联合事务已接入，C接带身份范围的日服务。V5已按V4/V4.1顺序发布，虹软SDK版本/平台/授权仍需后续接入；当前无官方SDK授权，不能以恒真逻辑代替识别。

## 验证

后端`sh ./mvnw verify`；前端`npm ci`、`npm run format`、`npm run build`，启动独立演示库后`npm run test:e2e`。云环境外部Playwright配置选择系统Chromium，开发机按README安装Playwright Chromium。

浏览器联调使用隔离临时H2，不修改用户已有数据，定位使用浏览器仿真；二维码图片由真实签名令牌生成并使用jsQR真实解码，没有用假的“识别成功”替代。具体结果见交接记录。

### MySQL后续验证

MySQL8.4.13上23项业务/并发/回滚测试及1项数据库重启后新JVM检查已通过；见[验证记录](../verification/member-b-mysql-2026-10-27.md)。写事务显式READ_COMMITTED，日查询仍为REPEATABLE_READ。A若提供外层审批事务，必须同时明确READ_COMMITTED，内部REQUIRED注解不能改变调用方隔离级别。

### 2026-10-28交互与手机准备更新

班次候选查询覆盖昨天/今天/明天，凌晨班次支持前一晚进入提前4小时窗口。排班页按请求版本隔离旧响应；扫码支持取消等待、丢弃旧图片与旧授权结果，切页/切后台释放设备。

新增认证路由`/attendance/device`检查当前设备环境，不提交打卡或人脸登记，不新增数据API。独立HTTPS验收工具见tools/mobile-acceptance/README.md；真机执行情况仍须单独填写验收表，不能以浏览器仿真代替。

2026-11-01人脸本机预览支持取消授权等待，晚到的旧授权/播放失败不会影响新预览；撤回授权、切页、切后台及pagehide释放摄像头，返回后需主动重新开启。打卡定位也丢弃失效页面的旧回调，按设备提供的定位时间判断新鲜度。SDK接入条件与验收顺序见[人脸接入交接](b-face-integration.md)。

### 2026-11-03审批与V5整合

接入A正式审批后，真实申请与审计、B调整共同提交/回滚。审批DATETIME读取使用JDBC LocalDateTime，避免UTC云环境与Asia/Shanghai数据库之间偏移8小时；原有日期/权限/幂等规则不放宽，不自动重写既有历史调整。人脸status在SDK未配置时`storageAvailable=true`而`available/detection/comparison/liveness=false`，不把建表当作识别成功。


## 课堂整站展示（2026-11-04）

展示台新增“一键整站演示”，在原有打卡与审批5步后调用C的正式报表、知识库和助手API，核对同一日记录及审批来源；无新增绕过权限的业务写入口。每轮合成制度生成DOCX，实际上传并等待解析结果，再按员工权限检索并核对引用。报表导出使用C现有CSV接口。失败会停止，不继续标记后续成功。流程、运行方式及边界见[课堂说明](../../tools/classroom/README.md)。

## 个人月度考勤视图

考勤记录页新增“个人月度考勤”，复用`GET /api/attendance/me/days?from=YYYY-MM-01&to=YYYY-MM-DD`，按整月查询本人正式排班与审批回写后的日结果。不增加服务端接口或迁移，不传入其他员工ID。下方原始打卡事件仍使用自己的日期筛选和分页，两处查询范围独立。

月历展示服务端状态、签到/签退、有效工时、获批请假/加班、迟到/早退、跨天提示、计算说明和审批来源ID。没有日记录显示“无排班记录”，不视作缺勤；PENDING按“待结算”展示。月度汇总只累计当前成功返回的日记录，不推算无记录日期的工时或出勤率。

导出按钮生成UTF-8 BOM的本人月度CSV，包含日期、状态、场所编号、北京时间、分钟数和审批来源；没有坐标、二维码、口令、人脸或其他员工数据。CSV单元格做引号转义及公式前缀处理。切换月份、身份或服务状态立即清除旧结果，使用请求版本检查拒绝迟到的旧响应；失败明确提示并禁用导出，可手动重试。月历与CSV是已有服务数据的只读呈现，不提交补卡或改变审批状态。


## B云端收尾与uni-app客户端

B收尾以A 1562571为共同基线，保留真实ArcSoftFaceProvider与可选/强制策略。SDK适配已实现，A用户已确认本人验证，不再将其标成尚未实现；云端仍不启用真实WindowsSDK或推断反例/完整含凭据打卡已通过。

独立mobile复用现有/auth/csrf、/auth/login、/auth/session、/auth/logout、/employees/me、/attendance/status、/attendance/me/workplaces、/attendance/me/punches、/attendance/me/punches/requests/{requestId}、/attendance/me/records、/attendance/me/days与/face/*。人脸更新继续multipart PUT并携带expectedRevision；原生端通过uni.request发送ArrayBuffer，无新增API或请求方法别名。

H5使用同源浏览器Cookie，小程序JSESSIONID/SESSION仅在运行内存维护并接受轮换；每个写请求取得当前CSRF，不重放失败写请求。C若更改会话Cookie名称，须协调该客户端的白名单及原生测试，不自行切换JWT。接口字段、审批和考勤口径未改变，A/C后端调用方无需改接；生产HTTPS、合法域名及静态反向代理由A/C部署接线。

未知结果只保存本人请求编号、方向和用户ID；允许另一个已登录同账号客户端手工输入原编号做只读回执核对。后端仍按当前身份查询，不转交人脸验证、不补发事件、不新增发现他人请求的能力。图像不写客户端持久业务存储，H5 Blob按授权/生命周期释放，二进制上传缓冲清零。

构建、平台差异与范围见[移动端](../../mobile/README.md)，现场未执行项见[手机清单](../team/member-b-mobile-checklist.md)。WindowsSDK静态检查脚本不激活SDK、不显示配置值，不代替实际人脸通过证据。


## B配合验收补充

本轮没有改变服务器接口。移动端增加采集取消/版本隔离、后台旧响应防护；待核对编号在认证确认后立即恢复，不因employees/me失败而漏恢复。设备验收只在本端生成OPERATOR_REPORTED_NOT_SERVER_ATTESTATION报告，未执行不自动标通过，含人脸打卡PASS需原始记录编号，A仍需核实。设备报告不作为服务端身份、授权或打卡凭据。

对C692d90c实际会话实现完成一次隔离组合验证：沿用JSESSIONID、逐次CSRF，B会话客户端交替请求两个C后端实例，真实MySQL/Redis、打卡/回执/退出通过。HTTP适配器验证不能替代原生微信或实际HTTPS。复现脚本scripts/verify-b-c-session.py依赖C工作区的隔离工具，源码只读复制到ignored测试目录，不更改或合并C分支。
