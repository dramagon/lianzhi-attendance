# A模块接口记录

负责人A。A1基础档案已接通，审批尚未实现。本机MySQL8.0.28迁移和集成测试已通过；整体仍为开发中，外部地图与设备定位待验证。

## 通用约定与权限

沿用Session、ApiResponse与CSRF。每次写请求先GET `/api/auth/csrf`，携带返回的headerName/token；前端复用 `write(method,url,data)`。列表参数page默认1（1～1000000）、size默认20（1～100），返回 `{items,total,page,size}`。

ADMIN维护全局；HR只查询本公司，维护本公司部门/场所及普通员工，不能维护或授予ADMIN/HR；EMPLOYEE只查询本人及本人绑定场所。HR尚未分配公司时拒绝公司业务，由ADMIN先设置归属。错误码：参数400、未登录/会话撤销401、越权403、不存在404、重复/引用冲突409；data=null。

## HTTP接口

| 方法 | 路径 | 参数或请求体 | 返回data |
| --- | --- | --- | --- |
| GET | `/api/organizations/companies` | 可选companyId，page/size | 分页Company |
| POST | `/api/organizations/companies` | `{name,enabled}`，仅ADMIN | null |
| PUT | `/api/organizations/companies/{id}` | 同上，仅ADMIN | null |
| GET | `/api/organizations/departments` | 可选companyId，page/size | 分页Department |
| POST | `/api/organizations/departments` | `{companyId,name,enabled}` | null |
| PUT | `/api/organizations/departments/{id}` | 同上 | null |
| GET | `/api/employees` | 可选companyId/query，page/size | 分页EmployeeView |
| GET | `/api/employees/me` | 无 | 本人EmployeeView |
| GET | `/api/employees/{userId}` | 范围内员工 | EmployeeView |
| GET | `/api/employees/{userId}/workplaces` | 范围内员工 | 全部绑定WorkplaceView数组，可含停用记录 |
| POST | `/api/employees` | EmployeeInput | EmployeeView |
| PUT | `/api/employees/{userId}` | EmployeeInput | EmployeeView |
| GET | `/api/workplaces` | 可选companyId，page/size | 分页WorkplaceView；员工仅本人有效绑定 |
| POST | `/api/workplaces` | WorkplaceInput | null |
| PUT | `/api/workplaces/{id}` | WorkplaceInput | null |

所有管理写接口限ADMIN/HR本公司，另有表内限制；EMPLOYEE无管理写权限。GET组织接口限ADMIN/HR。

## DTO与输入

- Company：`id,name,enabled`。Department：`id,companyId,name,enabled`。名称去首尾空白，1～100字。
- EmployeeView：`userId,username,employeeNo,displayName,role,companyId,companyName,departmentId,departmentName,enabled,createdAt`。userId就是sys_user.id，不新增登录身份；不含密码、哈希、auth_version。
- EmployeeInput：`username,displayName,employeeNo,role,companyId,departmentId,enabled,password,workplaceIds`。新增账号为3～50位字母/数字/点/横线/下划线；PUT不得改username，保留旧版短账号兼容。姓名1～100字，工号1～30字且全局唯一。role为ADMIN/HR/EMPLOYEE。
- 新增密码至少4位、UTF-8编码最多72字节，BCrypt落库；PUT的password省略或空字符串表示保留。非平台ADMIN必须有启用公司；部门可空，否则必须启用且同公司。workplaceIds必须提供，最多100个、不重复，空数组解除绑定；场所须启用且同公司。
- WorkplaceInput：`companyId,name,latitude,longitude,coordinateSystem,radiusMeters,enabled`；WorkplaceView增加id，其余相同。纬度[-90,90]、经度[-180,180]，最多7位小数；坐标系为WGS84/GCJ02/BD09；半径为10～10000米的整数。
- query匹配姓名/账号/工号，最长100字。所有分页与筛选在服务端执行；原 `/api/admin/accounts` 保留全量只读兼容行为。

## 关联与会话

管理员在“系统账号→分配账号”设置登录账号、初始密码和员工/人事/管理员角色；表格每行“管理账号”用于修改角色、重设密码及停用。维护复用已有员工接口，账号与档案使用同一个sys_user.id，不建立第二套身份。从该入口保存或取消后返回系统账号列表。“组织与场所→新增员工”也会同时建立登录账号和人员档案，页面已明确说明。员工和人事必须选择公司，未建公司时先维护组织。

不提供物理删除，采用停用保留历史引用。公司有启用员工/部门/场所时不能停用；部门有启用员工、场所有启用员工绑定时不能停用。部门和场所不直接迁移公司，先新建，再调整员工归属。员工迁移/启用时重新校验全部绑定，拒绝跨公司或失效引用。已停用员工的旧绑定保留，但有效场所服务排除停用员工/公司/场所。

最后一个启用ADMIN不能停用或降权。员工写事务使用READ_COMMITTED并按ID顺序锁定账号，串行保护管理员数量。员工更新递增auth_version，原有全部会话下次请求返回401并失效，包含角色、公司、密码和停用变更。前端401清除旧身份并跳回登录。

## B/C接入

1. 合并V2后再引入V3；保留V1。使用 `EmployeeService.get(Authentication,userId)` 获取经过范围检查的EmployeeView，或list查询分页范围。Authentication来自服务端上下文，不接受客户端指定调用者。
2. `DataScope.companyFilter(Authentication,requestedCompanyId)`用于公司过滤，manager/admin检查管理职责。具体员工使用EmployeeService，避免把客户端声明的companyId当成员工真实归属。
3. B使用 `WorkplaceService.activeForEmployee(Authentication,userId)`，按员工真实公司返回有效绑定，最多100个（绑定写入上限）。C展示历史可查询完整绑定。B计算距离前必须统一coordinateSystem。
4. 当前未实现考勤、审批、统计与AI业务，也不生成考勤记录。A2在V3和B的考勤调整服务公布后使用V4推进。

## 个人头像

所有启用账号（ADMIN、HR、EMPLOYEE）都可维护本人头像，未分配公司的HR仍可访问本人资料/头像，不能因此访问他人的公司业务。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | `/api/profile/avatar` | multipart/form-data，字段file；需CSRF，只写当前认证账号，返回更新后的UserView |
| DELETE | `/api/profile/avatar` | 需CSRF，恢复名称头像，返回更新后的UserView |
| GET | `/api/profile/avatars/{userId}` | 验证员工范围后返回image/jpeg，不存在404，不可匿名读取 |

UserView在原字段之外新增可空avatarUrl，登录/会话/个人资料/管理员账号列表同步返回。页面使用统一头像组件：默认显示姓名的简短名称，系统/演示管理员显示“管理员”，不再截取末尾的“理员”；上传后在顶栏、本人资料、员工首页和账号列表显示图片。

输入仅支持真实JPG/PNG，最大2MB、宽高不超过4096像素。服务端根据实际图片解码格式校验，忽略客户端文件名，不接受SVG或伪造图片；统一居中裁剪并编码为320×320 JPEG，只保存处理后的图片。上传不会改变登录身份或撤销自己的会话。

默认存放 `backend/data/avatars/`（运行目录为backend），通过数据源URL的散列子目录隔离不同数据库，再使用userId命名。可用AVATAR_DIRECTORY配置保存根目录；部署/备份须保留该目录，数据库与头像文件共同持久化。不使用请求路径写文件，不改V1/V2、不占用B的V3版本；真实头像和本机文件不提交Git。测试生成的图像存于临时目录或忽略目录。

## 地图与定位

场所页支持手工坐标、设备定位（WGS84，提示精度）与高德点击选点（GCJ02）。本机 `frontend/.env.local` 可配置 `VITE_AMAP_KEY`、`VITE_AMAP_SECURITY_CODE`，重启Vite生效；属于浏览器SDK配置，不要提交本机凭据。未配置显示“地图选点尚未接入”；加载失败明确提示，不伪造定位。

实现依据[高德官方选点示例](https://lbs.amap.com/demo/javascript-api-v2/example/map/click-to-get-lnglat/)和[安全配置说明](https://developer.amap.com/api/javascript-api-v2/guide/abc/jscode)。本轮没有真实凭据，不宣称外部地图或设备定位通过。

## 迁移与旧资料

V2新增org_company、workplace、employee_workplace，扩展sys_department/sys_user；保留V1、账号ID、原部门与密码哈希。旧版company_id保持空，不猜测归属。ADMIN先建公司/部门，再编辑员工明确选择归属和场所；旧部门迁出有效员工后可停用，仍由ADMIN维护。首次空演示库才生成明确标注的演示公司/部门/账号，已有库不重置、不重复生成。

## 运行记录与待办

2026-10-26 Windows，显式JDK17.0.12、Node24.12.0/npm11.6.2、Git、H2。原有版本后端10项与前端构建通过并成功启动。A1增加范围、引用、绑定、会话、管理员并发及V1升级验证，最终结果见[验证记录](../verification.md)。

数据库复核纠正了此前受限端口检查的误判：MySQL80原本已运行且监听3306；未重装/重启服务或重设root密码。使用用户提供的本机凭据建立 `lianzhi` 专用账号及库，V1/V2实际迁移通过；非演示模式仅创建一个初始管理员。初始化脚本复跑保留账号和数据。

`scripts/test-mysql.ps1` 在独立 `lianzhi_verify` 库运行29项检查，其中27项组织/认证/头像集成测试使用真实MySQL，2项迁移/并发隔离夹具仍使用H2，不将后两项称为MySQL验证。根凭据、应用密码和验证库密码均留在Git忽略目录，未提交。实际浏览器与重启结果见[验证记录](../verification.md)。剩余为外部地图、设备定位、A2审批和B/C完整链路。
