# 联智C整合包

本包把前端与Java后台打包为同源应用，不需要Node、Docker或MySQL就能使用独立演示库。需要Java17以上。Windows双击`start-c.cmd`，或运行`powershell -File .\start-c.ps1`；浏览器打开`http://127.0.0.1:8088`，演示账号admin/hr/employee，演示密码Lianzhi@2026，仅限本地合成资料体验。

默认只监听127.0.0.1，不改变系统服务。关闭终端或Ctrl+C停止；本包目录data中的数据库/头像会保留，下次启动继续使用。不要把data目录提交或发到聊天。包中不含原项目业务库、真实员工、人脸资料、密钥或测试SDK/模型替身。

知识库可上传文本型PDF、DOCX、XLS/XLSX、PPT/PPTX，后台解析、版本、文字检索和引用可直接使用。图片/扫描PDF的OCR、音视频转写、向量及模型需要真实服务。没有服务时明确显示未配置，不能把文字匹配叫向量检索或把界面存在叫AI已经接通。

可复制.env.example为本包目录.env，在本机填入配置后重新启动。问答/embedding用Ollama协议根URL与实际已安装的模型名；OCR/转写使用对应JSON接口，项目文档docs/modules/c-media-services.md给出契约。Windows启动脚本只读取允许的变量，不执行.env里的命令；密钥不要发到聊天。

要连接自己的MySQL，设置DB_URL、DB_USERNAME、DB_PASSWORD和首次初始化所需ADMIN_INITIAL_PASSWORD，运行`start-c.ps1 -Mysql`。使用新独立数据库验证，先备份已有业务库。本版Flyway顺序V1/V2/V3/V4/V4.1/V5/V6，不能把较早版本补到已使用较高版本的库或修改已发布SQL。

本包启用standalone静态页面配置，保持API登录、Session和CSRF；不启用B课堂演示自动造数页面。原始打卡、审批与报表使用真实业务服务，演示账号和自行上传的合成资料需与真实数据区分。制度回答必须有来源；AI申请建议不落库，仍须人工核对、预览和明确确认。
