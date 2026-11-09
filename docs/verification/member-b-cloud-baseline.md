# B云端收尾：整合基线与人脸凭据回归

基线为A整合分支1562571，保留其真实ArcSoftFaceProvider、可选人脸打卡与提交凭据消费、坐标转换、密码修改、主站AI和用户确认过的入口；同时保留B 558fd4a启动自检、185c2e0个人月历及CSV。收尾分支codex/member-b-cloud-finish，PR目标codex/member-a-release-validation。main不自动合并。

仅roadmap发生文本冲突，采用A最新事实并补充B自检/月历；没有把真实SDK恢复成“尚未实现”。旧B离线课堂摘录与A真实AI属于两种运行配置，均保留其明确边界。已发布迁移不改写。

新增3项后端业务回归，均使用src/test中的明确SDK替身：已验证但过期的可选凭据拒绝打卡，不静默退回普通成功；新二维码与更新登记令旧凭据失效且无事件写入；两个相同打卡请求并发消费仅落1条事件、返回相同编号，消费后仍可读取原回执。并发线程显式传播测试认证上下文，首次遗漏时真实权限校验拒绝，修正测试而未放宽权限。

完整后端verify：365项，失败0、错误0、跳过1；即364项执行通过，Windows真实SDK条件项未在云端运行。主站前端format/build通过，完整浏览器52项通过（A49项与B月历3项整合）。日志：/tmp/b-cloud-b1-backend-final.log、/tmp/b-cloud-b1-build.log、/tmp/b-cloud-b1-e2e.log。没有把SDK替身、主站浏览器仿真或此云端构建当作新一次真人活体/异人/照片/带凭据打卡现场验收。

后续独立mobile目录按cloud-b继续接入H5与小程序，复用现有Session/CSRF及业务API，不需要复制A的SDK、密钥、数据库或真实资料。Windows与手机最终由A现场逐项验收。
