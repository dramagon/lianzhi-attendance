# B模块：考勤与人脸

本轮已接入考勤工作台、真实服务端规则试算、受保护的打卡/记录接口及人脸能力状态。**不是完整 B 模块验收**：A 的 V2、员工/场所/公司范围适配器尚未存在；当前真实打卡和存储查询明确返回503。没有向现有数据库添加越序迁移或模拟考勤。

## 页面与使用

登录任一现有身份 → 左侧「考勤工作台」。四个页签：

- 今日打卡：展示服务准备情况。依赖齐备后使用授权场所的短时挑战令牌和设备 WGS84 定位，选择上班/下班并确认提交。当前是令牌输入，不宣称已完成二维码图像呈现或相机扫码。
- 考勤记录：本人日期范围查询、分页、加载/失败/未接入/空状态。不接受前端提供的其他 userId。
- 排班与规则：通过后端真实计算正常、迟到、早退、缺卡、缺勤、待结算、休息日和跨天班次；快速样例均为试算输入，不创建出勤记录。字段改变清除旧结果，并丢弃已过时的请求结果。
- 人脸管理：真实查询SDK接入状态。用户勾选授权后可本机预览摄像头；关闭、撤回授权、切页时释放设备。没有截图、上传、特征生成或伪造验证。

手机页面包含横向页签、可滚动表格、按钮防重复提交、定位与摄像头错误提示。仅验证浏览器响应式布局，不等于真实手机/小程序/uni-app已完成。

## API

全部接口需现有登录Session；POST还需`GET /api/auth/csrf`返回的请求头。沿用`ApiResponse`。当前用户由AccountService/认证上下文解析；每次校验账号启用状态。

| 方法 | 路径 | 行为与范围 |
| --- | --- | --- |
| GET | `/api/attendance/status` | 返回available、rulesAvailable、directoryAvailable、storageAvailable、blockers、timeZone、serverTime |
| POST | `/api/attendance/rules/preview` | 已登录用户的无副作用规则试算，不保存排班 |
| GET | `/api/attendance/me/workplaces` | 由A适配器返回当前员工可用场所；未接入503 |
| POST | `/api/attendance/workplaces/{workplaceId}/challenge` | ADMIN/HR且A适配器允许管理该场所；短时签名挑战；员工403 |
| POST | `/api/attendance/me/punches` | 本人签到/签退；缺依赖503；不信任客户端时间或userId |
| GET | `/api/attendance/me/records?from=YYYY-MM-DD&to=YYYY-MM-DD&page=1&size=20` | 本人原始事件；最大94天、size 1–100，返回items/total/page/size |
| GET | `/api/face/status` | 当前detection/comparison/liveness均false，provider=NONE |
| POST | `/api/face/enrollment`、`/api/face/verification` | 当前503，不产生任何登记或验证凭据 |

规则请求示例：

```json
{
  "date": "2026-10-26",
  "rule": {
    "start": "09:00", "end": "18:00",
    "breakStart": "12:00", "breakEnd": "13:00",
    "graceMinutes": 5, "workdays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"]
  },
  "checkIn": "2026-10-26T09:12:00", "checkOut": "2026-10-26T18:00:00",
  "holiday": false, "finalized": true
}
```

返回`status=LATE,lateMinutes=12,earlyMinutes=0,workMinutes=468,breakMinutes=60,overnight=false`及解释notes。

规则口径：Asia/Shanghai，业务日期归班次开始日；下班早于上班即跨天，签退输入必须带正确日期。签到延迟在宽限内不计迟到，超过后按总延迟向上取整到分钟；早退同样向上取整。工时只计班次内实际出勤，扣除与休息段的重叠秒数后向下取整到分钟；不自动认可加班。未结束班次缺事件为PENDING，结算后全缺为ABSENT、单缺为MISSING_PUNCH，均不虚构工时。休息日不计算迟到早退和工时。非法时间顺序、越界休息、超过班次前后12小时事件均拒绝。

打卡请求（仅依赖接通后）：

```json
{"type":"IN","token":"服务端发放的签名令牌","requestId":"客户端UUID","location":{"latitude":31.23,"longitude":121.47,"coordinateSystem":"WGS84"},"accuracyMeters":20}
```

令牌HMAC-SHA256签名，绑定场所、60秒有效期与随机nonce，恒定时间验签；进程重启或多实例中其他进程的令牌失效。**当前仅支持单实例**，多实例需共享密钥/轮换方案再启用。共享令牌允许不同员工使用；数据库按(userId,requestId)、(userId,businessDate,type)、(userId,nonce)独立唯一防重。事务锁定当前用户行以串行化班次事件；先签到再签退。有效令牌窗口内同requestId和相同场所/类型重试返回原事件，令牌到期应查询记录后再决定重试。接口不返回requestId、nonce和原始坐标。

定位仅接受WGS84、有限数值与有效范围；Haversine计算距离。定位精度必须正数且不大于min(场所半径,100米)，场所半径1–5000米。GCJ02/BD09必须先由场所提供方进行明确转换，当前拒绝混用。浏览器坐标不构成防作弊证明，真实设备定位与风控仍待联调。

错误：401未登录、403角色/CSRF/范围拒绝、400 `ATTENDANCE_INVALID_INPUT`、409 `DUPLICATE_PUNCH`、503 `ATTENDANCE_NOT_READY`。异常处理只作用于B控制器。

## 给A的接入点

实现并注册`AttendanceDirectory` Spring Bean，三项能力必须校验真实员工启用状态、公司权限、场所启用状态及绑定：

1. `workplaces(userId)`：返回该用户可用场所，不泄露跨公司场所。
2. `forPunch(userId,workplaceId,Instant now)`：在服务端按北京时间解析**真实班次分配和业务日期**（含跨天/工作日/调休），返回Assignment；没有班次必须拒绝，不能使用固定演示班次兜底。
3. `requireManager(managerId,workplaceId)`：校验管理员/人事的实际公司与场所管理范围。

`docs/modules/b-pending-migrations/V3__attendance.sql`是未激活的原始事件表草案，H2隔离测试已执行。A的V2合并后先核对真实workplace表名、外键和排班模型，再补齐排班/日记录表，整体评审V3后才移入Flyway目录。不得直接在已有库提前应用此草案，也不要打开out-of-order。

目前没有实现按申请ID幂等的审批调整服务，A不能调用不存在的服务；后续需追加可追溯调整记录，保留原始事件。公共变动仅根路由引入B的routes.js、菜单增加考勤入口，未重写认证、identity、布局结构或全局样式。

## 给C的口径

复用`AttendanceRules.calculate`，不重复计算迟到/早退/工时。当前EventView只用于本人接口；跨员工/公司日报、报表查询和审批调整后的统一日记录尚待A范围接口及日记录模型，不应直接调用AttendanceStore绕过授权。不会以当前原始事件查询冒充已完成的日报服务。

## 实际验证与剩余工作

运行命令：后端`sh ./mvnw verify`；前端`npm run format`、`npm run build`；先启动演示后端再运行`npm run test:e2e`。云环境使用外部Playwright配置`/workspace/.onboarding/playwright.config.mjs`选择系统Chromium；普通环境沿用仓库Playwright安装步骤。

本次云端验证：后端45项测试全部通过（含原有10项），前端生产构建通过，浏览器11项全部通过（含原有5项）；没有失败或跳过。

测试覆盖：原有登录/权限，规则边界，二维码篡改/到期/共享使用，定位精度/范围/坐标系，隔离H2中的事件持久化/本人隔离/幂等/并发/顺序，CSRF和依赖关闭；浏览器覆盖真实试算、错误提示、缺依赖状态、摄像头拒绝/释放、无上传和手机布局。

**尚未验收**：A适配与V2→V3全量迁移、正式排班保存/管理、扫码图像与相机扫码、日记录/审批调整/授权管理查询、人脸V5与真实虹软SDK检测/比对/活体、真实MySQL、Windows、真机定位与相机、HTTPS部署、uni-app/小程序。SDK授权与设备只能在接入真实平台时验证，不需要为了当前规则试算填写任何密钥。
