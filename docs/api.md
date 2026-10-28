# 接口与权限约定

三人协作API分别记录在 [A：组织、员工与场所（A1已接通）](modules/a-api.md)、[B：考勤与人脸](modules/b-api.md)、[C：报表与AI](modules/c-api.md)。跨模块约定见 [公共约定](team/contracts.md)。下文保留基础接口。

A2.2已接通 `/api/approvals` 的真实提交、分页记录、详情、审批/拒绝/撤回与审计，审核和B考勤调整同事务；状态和内容校验接口保留。所有写接口需CSRF，管理访问按公司范围，详见[A模块文档](modules/a-api.md)。

浏览器通过同源 `/api` 访问。成功响应格式：`{"code":"OK","message":"操作成功","data":...}`。认证失败为 HTTP 401；权限或 CSRF 校验失败为 HTTP 403；错误响应的 `data` 为 null。

## 登录流程

1. `GET /api/auth/csrf` 获取 `{headerName, token}`，浏览器保留会话 Cookie。
2. `POST /api/auth/login`：请求体为 `application/x-www-form-urlencoded`，字段 `username`、`password`，携带上一步的 CSRF 头。
3. 登录成功返回当前用户和权限列表，Spring Security 轮换会话标识；Cookie 为 HttpOnly、SameSite=Lax。
4. 页面刷新通过 `GET /api/auth/session` 恢复身份。
5. 退出前重新获取 CSRF token，再 `POST /api/auth/logout`；旧会话失效。

会话空闲超时为30分钟。密码与会话凭据不存入 localStorage。每次写请求前重新获取 CSRF token，前端复用 `write(method, url, data)`。员工资料、角色、公司、密码或启用状态更新后，旧会话下一次请求返回401并失效。

## 接口表

| 方法 | 地址 | 匿名 | 管理员 | 人事 | 员工 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/system/info` | 允许 | 允许 | 允许 | 允许 |
| GET | `/api/auth/csrf` | 允许 | 允许 | 允许 | 允许 |
| POST | `/api/auth/login` | 需要 CSRF | 需要 CSRF | 需要 CSRF | 需要 CSRF |
| POST | `/api/auth/logout` | 需要 CSRF | 需要 CSRF | 需要 CSRF | 需要 CSRF |
| GET | `/api/auth/session` | 401 | 允许 | 允许 | 允许 |
| GET | `/api/profile` | 401 | 自己 | 自己 | 自己 |
| POST | `/api/profile/avatar` | 401 | 自己，需CSRF | 自己，需CSRF | 自己，需CSRF |
| DELETE | `/api/profile/avatar` | 401 | 自己，需CSRF | 自己，需CSRF | 自己，需CSRF |
| GET | `/api/profile/avatars/{userId}` | 401 | 全局 | 本人或同公司 | 本人 |
| GET | `/api/management/overview` | 401 | 允许 | 允许 | 403 |
| GET | `/api/employee/overview` | 401 | 403 | 403 | 自己 |
| GET | `/api/admin/accounts` | 401 | 允许 | 403 | 403 |
| GET | `/api/system/modules` | 401 | 允许 | 允许 | 允许 |

账号响应使用 `UserView`，不包含密码哈希。后端通过角色校验授权，前端隐藏菜单和路由守卫只是用户体验的一部分。

UserView新增可空 `avatarUrl`；所有角色可在个人信息上传/移除本人头像。头像GET成功返回JPEG二进制，其他头像接口返回ApiResponse<UserView>，上传限制与文件保存说明见[A模块文档](modules/a-api.md)。账号初始/重设密码最低4位，UTF-8最多72字节，前后端及MySQL启动脚本一致。

## 当前数据范围

- 管理看板读取数据库中的账号、启用账号、已使用角色与部门数量；ADMIN看全局，HR仅看所属公司，未分配公司的HR显示0且不允许访问公司业务接口。
- 日期按 Asia/Shanghai 计算。
- 考勤能力返回 `attendanceAvailable: false`；前端显示待接入，不把“尚未统计”当作0次缺勤。
- 原管理员账号GET查询保留全量读取行为；“系统账号”页提供“分配账号”和“管理账号”入口，维护复用 `/api/employees`，执行服务端公司范围及本人范围检查，详见A模块文档。
- 模块进度来自服务端维护的开发清单，不是用户的考勤或工作完成记录。

## 后续扩展边界

A1已接通公司、部门、员工和场所基础维护，本机MySQL已通过迁移及集成验证；外部地图仍待独立验证。B负责考勤规则和打卡、独立人脸识别适配层；A2审批已在B正式V3之后接通V4和调整服务。真实密钥和人脸数据均不写入仓库。

## B模块

考勤接口、迁移、排班与联调边界见[B接口文档](modules/b-api.md)。
