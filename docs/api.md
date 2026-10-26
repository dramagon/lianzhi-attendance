# 第一阶段接口与权限约定

三人协作的新增API分别记录在 [A：组织与审批](modules/a-api.md)、[B：考勤与人脸](modules/b-api.md)、[C：报表与AI](modules/c-api.md)。跨模块约定见 [公共约定](team/contracts.md)。下文保留已实现的第一阶段接口。

浏览器通过同源 `/api` 访问。成功响应格式：`{"code":"OK","message":"操作成功","data":...}`。认证失败为 HTTP 401；权限或 CSRF 校验失败为 HTTP 403；错误响应的 `data` 为 null。

## 登录流程

1. `GET /api/auth/csrf` 获取 `{headerName, token}`，浏览器保留会话 Cookie。
2. `POST /api/auth/login`：请求体为 `application/x-www-form-urlencoded`，字段 `username`、`password`，携带上一步的 CSRF 头。
3. 登录成功返回当前用户和权限列表，Spring Security 轮换会话标识；Cookie 为 HttpOnly、SameSite=Lax。
4. 页面刷新通过 `GET /api/auth/session` 恢复身份。
5. 退出前重新获取 CSRF token，再 `POST /api/auth/logout`；旧会话失效。

会话空闲超时为30分钟。密码与会话凭据不存入 localStorage。每次登录或退出前重新获取 CSRF token，避免使用跨会话旧令牌。

## 接口表

| 方法 | 地址 | 匿名 | 管理员 | 人事 | 员工 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/system/info` | 允许 | 允许 | 允许 | 允许 |
| GET | `/api/auth/csrf` | 允许 | 允许 | 允许 | 允许 |
| POST | `/api/auth/login` | 需要 CSRF | 需要 CSRF | 需要 CSRF | 需要 CSRF |
| POST | `/api/auth/logout` | 需要 CSRF | 需要 CSRF | 需要 CSRF | 需要 CSRF |
| GET | `/api/auth/session` | 401 | 允许 | 允许 | 允许 |
| GET | `/api/profile` | 401 | 自己 | 自己 | 自己 |
| GET | `/api/management/overview` | 401 | 允许 | 允许 | 403 |
| GET | `/api/employee/overview` | 401 | 403 | 403 | 自己 |
| GET | `/api/admin/accounts` | 401 | 允许 | 403 | 403 |
| GET | `/api/system/modules` | 401 | 允许 | 允许 | 允许 |

账号响应使用 `UserView`，不包含密码哈希。后端通过角色校验授权，前端隐藏菜单和路由守卫只是用户体验的一部分。

## 当前数据范围

- 管理看板读取数据库中的账号、启用账号、已使用角色与部门数量。
- 日期按 Asia/Shanghai 计算。
- 考勤能力返回 `attendanceAvailable: false`；前端显示待接入，不把“尚未统计”当作0次缺勤。
- 账号查询当前返回全部账号，面向小规模演示。第二阶段完善员工维护时加入后端分页与组织权限。
- 模块进度来自服务端维护的开发清单，不是用户的考勤或工作完成记录。

## 后续扩展边界

第二阶段添加公司、部门、员工和场所维护；第三阶段定义考勤规则与打卡接口；第四阶段通过独立的人脸识别适配层接入 SDK。角色权限随业务操作逐步细化，真实 SDK 密钥和人脸数据均不写入仓库。
