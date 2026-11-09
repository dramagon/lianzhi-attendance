# 知识库与AI接口

文档处理、权限检索、有来源问答与申请建议的接口说明。

## 持久化与权限

V6在V1/V2/V3/V4/V4.1/V5后应用，保存文档当前原件、切片、向量、版本审计和数据库队列。未新增不必要的V7。知识库默认启用；显式设置 `LIANZHI_KNOWLEDGE_ENABLED=false` 可关闭业务入口。

员工只能管理自己的 PRIVATE 文档；公司公开制度由本公司HR/管理员管理。员工读取本公司 COMPANY 或自己的 PRIVATE；HR读取本公司，管理员按平台范围读取。上传公司、文档所有人、检索、下载和引用均服务端校验，不采信客户端userId。模型调用前后重验身份版本、权限与文档版本。

| 方法与路径（均以 `/api` 开始） | 行为 |
| --- | --- |
| GET knowledge/status | 实际配置与存储可用性；配置不等于服务可达 |
| GET knowledge/documents?page=1&size=20 | 授权分页，最大size100 |
| POST knowledge/documents | multipart companyId、visibility、file；Session+CSRF |
| GET knowledge/documents/{id} | 当前版本和处理/索引状态 |
| PUT knowledge/documents/{id} | multipart expectedVersion、visibility、file |
| DELETE knowledge/documents/{id}?expectedVersion=N | 版本校验后清除原件、正文及向量 |
| POST knowledge/documents/{id}/retry?expectedVersion=N | 重排队并增加版本，旧引用立即失效 |
| GET knowledge/documents/{id}/file | 当前原件，attachment/no-store/nosniff |
| GET knowledge/documents/{id}/revisions | 元数据历史，不保留旧原文 |
| GET knowledge/documents/{id}/sources/{sequence}?version=N | 当前有权限版本的引用，旧版本404 |
| GET knowledge/search?query=...&mode=TEXT或VECTOR&limit=10&minimumScore=0 | 权限内匹配/真实向量精确余弦检索 |
| POST assistant/ask | JSON question；有依据/无依据/无效响应/来源变化明确区分 |
| POST assistant/approval-suggestions | JSON instruction；只建议，绝不提交申请 |

错误沿用ApiResponse与公共异常：401未登录、403越权/CSRF、404无可见来源、409版本/容量冲突、413超大上传、503未配置/服务不可用；模型无效草稿502。既有草稿预览、明确确认和幂等仍使用申请确认接口及普通业务校验。

数据库队列 Spring Task 每2秒执行；解析/网络IO不持有数据库写锁。PROCESSING有5分钟租约，重启中断进入FAILED，由用户显式重试；更新/删除后的旧任务不能写回。文本型PDF/DOCX/XLS/XLSX/PPT/PPTX由独立256MiB/45秒Java进程解析；旧DOC明确不支持。OCR/音视频支持范围见[真实服务说明](media-services.md)。

限额：10MiB每文件、100个活跃文档/1000条总文档、原件总量100MiB、每文档50版（达到上限仍可删除）、200切片、总20000切片/400万文字码点/100万向量值。删除保留少量审计与墓碑元数据。当前检索为有界数据库载入后的精确计算，不称大规模向量数据库。

## 报表、预测与地图

沿用[报表口径](reports.md)及统一考勤结果。新增hours返回24小时有效签到/签退计数，使用考勤服务已处理的有效时间，包含批准补卡，跨夜按真实小时，不是设备原始事件总量。ECharts展示真实有效工时和批准调整趋势，保留原记录回查与CSV同范围授权。历史少于14个有效完成日不预测；足够时用时间顺序留出的误差比较历史均值与线性基线，展示7日预估及MAE，仅作历史趋势参考。不能将其称为训练完成的考勤分类模型。

AMap真实热力图需用户自己的地图凭据并点击加载；WGS84/BD09通过官方convertFrom每批最多40点转换，GCJ02直接使用，超时/失败明确提示。没有凭据仍可看授权场所汇总和相对热度图；地图替身回归不算真实地图服务验收。[坐标转换官方文档](https://lbs.amap.com/api/javascript-api-v2/guide/transform/convertfrom)、[热力图官方示例](https://lbs.amap.com/demo/javascript-api-v2/example/selflayer/heatmap)、[ECharts按需导入](https://echarts.apache.org/handbook/en/basics/import/)。
