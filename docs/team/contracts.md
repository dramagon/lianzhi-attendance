# 三人公共约定

以下是下一阶段的开发约定，不代表已经存在全部类或接口。各成员要把实际完成的接口写入自己的模块文档。

## 身份、权限与数据

- 员工身份沿用 `sys_user.id`，业务接口中称 `userId`；不建立互不关联的第二套登录身份。
- A扩展现有 `sys_department`、员工、公司、场所及绑定。保留V1迁移。
- 当前用户从后端认证上下文取得。ADMIN按平台职责访问，HR按所属公司访问，EMPLOYEE默认只访问自己的业务和有权阅读的制度。A先提供共享范围判断，B/C在后端复用。
- 业务时区统一Asia/Shanghai，打卡时间由后端生成。位置字段统一latitude/longitude，记录坐标系并校验；地图与设备定位坐标不一致时明确转换后再计算距离。
- 原始打卡、申请和调整记录保留来源；B统一计算工时和考勤状态，A/C不重复实现另一套口径。

## 提供方与消费方

| 能力 | 提供方 | 消费方 | 最低字段/行为 |
| --- | --- | --- | --- |
| 员工与可访问范围 | A | B/C | userId、工号、姓名、公司/部门ID、启用状态；校验调用者范围 |
| 办公场所 | A | B/C | 场所/公司ID、名称、经纬度、坐标系、打卡半径、启用状态 |
| 日考勤/事件查询 | B | A/C | userId、日期、场所、打卡事件、异常状态、有效工时、查询范围 |
| 申请与审批结果 | A | B/C | 申请ID、申请人、类型、时间范围、状态、审批时间 |
| 审批调整考勤 | B | A | 用申请ID保证幂等，保留原始记录与调整来源 |
| 人脸验证 | B | 打卡服务 | 后端验证，绑定身份/挑战/请求，不接受客户端“识别成功”布尔值 |
| 报表与知识检索 | C | 页面/AI工具 | 权限过滤、数据口径、来源引用与可追溯结果 |

提供方先公布真实服务接口和DTO，再由消费方接入；模块化单体内优先使用Java服务调用，不为普通查询额外制造分布式依赖。

## API与公共文件

沿用ApiResponse、服务端Session、HttpOnly Cookie、CSRF。A补齐共享写请求CSRF辅助方法、输入校验、业务异常和数据范围判断。不能为解决403放开整个API。

接口前缀：A使用 `/api/organizations`、`/api/employees`、`/api/workplaces`、`/api/approvals`；B使用 `/api/attendance`、`/api/face`；C使用 `/api/reports`、`/api/knowledge`、`/api/assistant`。分页统一page（从1开始）/size，返回items/total/page/size，并设服务端上限；查询和导出都执行权限过滤。

| 成员 | 后端业务包（cn.lianzhi.attendance下） | 前端目录 | 模块接口文档 |
| --- | --- | --- | --- |
| A | organization、employee、workplace、approval | frontend/src/modules/organization、approval | docs/modules/a-api.md |
| B | attendance、face | frontend/src/modules/attendance | docs/modules/b-api.md |
| C | report、knowledge、assistant | frontend/src/modules/intelligence | docs/modules/c-api.md |

各模块导出routes.js。A在整合时连接根路由和菜单，仅引入已经合并存在的文件。A协调现有identity/config/common/web、认证store、共享api.js、根路由/布局/README/整体进度，以及公共依赖变更；B/C在PR里列出必要调整，避免分别重写同一文件。C提供部署/CI配置并与A核对公共构建设置。

新增业务API分别写入成员文档，docs/api.md保留统一入口。

## Flyway与合并顺序

| 版本 | 负责人 | 内容 |
| --- | --- | --- |
| V1 | 已存在 | 账号与部门 |
| V2 | A | 公司、员工扩展、场所与绑定 |
| V3 | B | 排班、规则、打卡与日记录 |
| V4 | A | 申请与审批 |
| V5 | B | 人脸登记/验证元数据 |
| V6 | C | 文档、切片、知识检索/会话所需数据 |
| V7 | C | 报表任务/导出记录，仅确有需要时使用 |

迁移按此依赖顺序随各自的小阶段PR合并，不能先应用V6再补V2～V5。前置迁移未完成时，可以先交付纯逻辑和页面，但依赖能力明确关闭。新增版本统一登记；不需要某个版本时共同调整后续顺序。

各人使用独立本机开发库，整合时用另一个空库验证全量迁移。若分支已应用较高版本后又接入较低版本，先检查Flyway状态，使用新的演示库验证；不修改已发布迁移，不盲目启用out-of-order，不删除有价值的数据。

## 完成标准

- 业务页面、真实API、持久化、权限和适当测试一并交付，演示数据明确标注。
- 人脸检测、比对、活体分别验收；SDK未配置返回明确状态，不伪造识别成功。
- AI回答引用文档或真实业务数据；写操作经用户确认，再通过普通业务服务重新校验权限并保证幂等。
- 真实MySQL、Windows、SDK、地图、AI服务和手机权限逐项记录实际运行情况，缺配置不算功能已通过。
- 现有回归测试保留；因需求变更调整断言时说明原因，不能删除测试掩盖失败。
- 每阶段验证、commit、push个人分支并创建/更新PR；确认远端SHA后再报告同步成功。
