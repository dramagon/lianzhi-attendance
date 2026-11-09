# 接口与权限

接口使用服务端Session、HttpOnly Cookie和CSRF。浏览器通过同源 `/api` 访问，身份及数据范围由服务端核对。

| 业务 | 文档 |
| --- | --- |
| 账号、组织、员工、场所与审批 | [组织与审批](modules/organization-api.md) |
| 排班、考勤与人脸 | [考勤](modules/attendance-api.md) · [人脸配置](modules/face-setup.md) |
| 报表与导出 | [报表](modules/reports.md) |
| 知识库与AI | [知识库](modules/knowledge-api.md) · [申请确认](modules/assistant-actions.md) |
| 共享登录 | [Redis会话](modules/redis-session.md) |

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

UserView新增可空 `avatarUrl`；所有角色可在个人信息上传/移除本人头像。头像GET成功返回JPEG二进制，其他头像接口返回ApiResponse<UserView>，上传限制与文件保存说明见[A模块文档](modules/organization-api.md)。账号初始/重设密码最低4位，UTF-8最多72字节，前后端及MySQL启动脚本一致。

## 数据范围

员工默认访问本人资料、考勤、申请，以及本人或所属公司有权阅读的文档。人事按所属公司管理，管理员按平台范围管理。前端菜单与路由守卫辅助展示，后端始终重新校验权限。

账号响应不包含密码哈希。角色、密码、公司或启用状态变更会使旧会话失效。原始打卡与审批调整分别保留来源，报表及助手复用统一考勤口径。

## 可选能力

人脸、模型、向量、OCR及转写状态以对应状态接口为准。未配置或服务不可用时返回明确错误，不生成成功记录。模型申请建议只生成草稿，写入必须经过预览与用户明确确认。Redis不可用时共享会话请求返回503，不回退为本地身份。
