# 正式 MySQL 与真实本地 AI 组合运行

## 运行条件

- Java 17+；Python 3.11 环境安装 `tools/c-media/requirements.txt`。
- 真实 Ollama 运行时和已下载的问答、embedding 模型。默认仍为 `qwen2.5:1.5b`、`qwen3-embedding:0.6b`，可通过环境变量修改。
- 图片/扫描 PDF 和音视频需要 RapidOCR、Whisper 模型及 FFmpeg/ffprobe；`--text-only` 可只启动真实问答和向量服务。
- MySQL 8 空库或已按迁移管理的现有库、对应账号及 DDL/DML 权限。启动器不创建数据库、不修改账号密码、不导入演示员工；第一次空账号库由既有 AccountBootstrap 使用 `ADMIN_INITIAL_PASSWORD` 初始化管理员。
- 最新源码构建的同源 JAR：`python scripts/build-c-package.py`。模型、运行时和业务库不包含在 JAR 中；安装说明见 [本机服务](media-services.md)。

## Windows 启动

复制 `deploy/c-mysql-ai.env.example` 到忽略目录 `artifacts/c-mysql-ai.env`，在本机填写数据库配置。配置采用逐行字面值，不执行命令、展开变量或输出密码。不要将 `.env` 改为可执行脚本。

```powershell
& artifacts/c-media-venv/Scripts/python.exe tools/c-package/run-full.py --mysql --env-file artifacts/c-mysql-ai.env --check
& artifacts/c-media-venv/Scripts/python.exe tools/c-package/run-full.py --mysql --env-file artifacts/c-mysql-ai.env
```

`--check` 只检查必要配置、路径与 Java 版本，不启动服务、不写入数据，也不证明数据库/推理服务联通。实际启动后浏览器默认访问 `http://127.0.0.1:8088`，只绑定回环地址。Ctrl+C 停止本次创建的进程；数据保留。发现端口占用会停止启动，不附着或终止其他人的服务。

MySQL 模式强制 `mysql,standalone` 和 `lianzhi.demo=false`，校验 DB_URL 必须为 `jdbc:mysql://`，不接受 H2 课堂库复用。默认工作目录为 `artifacts/c-mysql-data`，与演示目录分开；数据库原件、切片和向量仍存 MySQL，头像及日志位于工作目录。确认草稿和会话仍在内存中，重启必须重新登录/核对申请，不能宣称已具备 Redis 会话恢复。

`start-ai.cmd` 入口和其虹软凭据加载文件保持原样。需要虹软的正式组合运行时，先在 A 原有本机上下文加载其私有配置，再执行上述 Python 启动；启动器保留继承的 SDK 环境变量，不复制 SDK 或授权文件。

## Linux 与自定义路径

同一 Python 入口使用平台对应的可执行文件名，Linux 默认 venv 位于 `artifacts/c-media-venv/bin/python`、Ollama 位于 `artifacts/ollama-cpu/ollama`。不将 Windows 虹软能力计入 Linux 验收。

```sh
python3 tools/c-package/run-full.py --mysql --env-file artifacts/c-mysql-ai.env --no-browser
```

| 配置 | 含义 |
| --- | --- |
| `--jar` | 指定已构建 JAR；未指定时读取项目 `artifacts/c-package-latest.json` |
| `--runtime-directory` / `LIANZHI_RUNTIME_DIRECTORY` | 模型与运行时根目录，可复用另一个工作区的安装 |
| `--data-directory` / `LIANZHI_DATA_DIRECTORY` | 应用工作目录、日志和头像；不改变 MySQL 库名 |
| `--java` / `JAVA_HOME` | Java 17+ 运行环境 |
| `--ollama` / `LIANZHI_OLLAMA_EXECUTABLE` | Ollama 可执行文件 |
| `--media-python` / `LIANZHI_MEDIA_PYTHON` | 安装媒体推理依赖的 Python 可执行文件 |
| `OLLAMA_MODELS` | 已下载的模型目录 |
| `LIANZHI_WHISPER_MODEL` | Whisper 模型目录，须含 model.bin |
| `LIANZHI_FFMPEG_DIRECTORY` | FFmpeg 与 ffprobe 的目录；否则从 PATH 查找 |
| `--port` / `--ollama-port` / `--media-port` | 默认 8088 / 11439 / 11440，须互不相同 |
| `LIANZHI_AI_MODEL` / `LIANZHI_EMBEDDING_MODEL` | 保留现有准确模型标签；启动时检查已安装 |
| `LIANZHI_AI_TIMEOUT_SECONDS` | 模型请求超时，后端仍校验 1～60 秒 |

这是单机组合启动器，自行创建本地 Ollama/媒体服务，生成本次媒体令牌，通过环境传递。连接远端已部署服务时继续使用既有 `start-c.ps1 -Mysql` 或后端环境配置，不使用本地组合入口。

## 独立真实服务验收

以下脚本初始化一个全新目录的 MySQL 实例，选取空闲端口、随机生成测试密码，启动本次独占的 AI 和应用进程。不会连接或停止系统 MySQL 服务。通过正式管理员/员工 API 创建合成组织与申请；仅在新测试库中直接模拟解析任务过期租约。

```powershell
& artifacts/c-media-venv/Scripts/python.exe scripts/make-c-ai-fixtures.py
& artifacts/c-media-venv/Scripts/python.exe scripts/verify-c-mysql-ai.py --mysql-bin 'C:/Program Files/MySQL/MySQL Server 8.0/bin' --runtime-directory artifacts --jar backend/target/lianzhi-backend-0.1.0.jar --fixtures artifacts/c-ai-fixtures
```

`make-c-ai-fixtures.py` 默认生成 `artifacts/c-ai-fixtures`，对应验收时使用 `--fixtures artifacts/c-ai-fixtures`；也可传已有合成资料目录。三个文件为 `real-ocr-fixture.png`、`real-ocr-fixture.pdf`、`real-asr-fixture.wav`。生成器使用中文字体、位图扫描 PDF 和系统合成语音，保留已有同名文件；Windows 需中文语音，Linux 需 Noto CJK 字体与 espeak-ng（普通话语音）。不传 `--fixtures` 则只验证真实文本模型链路，媒体结果留空。脚本不安装或下载运行依赖。

结果写入独立 `artifacts/c-mysql-ai-*/result.json`，失败也保留证据。测试数据/日志留在该目录；退出时关闭自己的进程并删除临时密码配置。检查范围包括真实模型带引用回答、OCR/转写及向量、跨公司/CSRF、模型建议不落库、用户确认及数据库防重、审批回写与考勤/报表/助手同口径、提供方停机恢复、MySQL/应用重启、任务租约及显式重试、身份变更和删除后引用失效。不替代生产环境验收。

轻量启动边界回归：`python -m unittest discover -s scripts -p test_c_runtime.py -v`。新增 Windows/Linux CI 只验证启动配置边界；不下载模型，不将配置测试称为真实推理或 Linux 全链路通过。

H2演示/测试模式附加 `OPTIMIZE_REUSE_RESULTS=FALSE`，避免项目当前H2版本在等待锁前后复用过期查询结果。课堂库复用入口同样保留该设置；自行覆盖H2 URL时也应追加。此配置不用于MySQL。


模型建议按明确类别与绝对起止时间约束生成，并在服务端复核；替换用户明确输入时拒绝写入。`--require-suggestion-ready`要求使用真实模型草稿完成确认验证；`--verify-backup-restore`验证合成库备份恢复。
