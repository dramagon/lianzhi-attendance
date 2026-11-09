# C本机真实服务与启动

2026-11-08：新增[正式 MySQL 与真实 AI 组合启动](c-mysql-ai.md)，支持显式非演示库、依赖与端口配置，以及独立真实服务验收；下文默认演示入口继续保留。

本机已在忽略目录 `artifacts` 安装独立 CPU 运行环境，未更改 MySQL80 等已有系统服务。无需申请付费 API，也不用把密钥发到聊天。仓库不包含模型、运行时、令牌、原始文件或数据库。

## 当前电脑

A 的 Windows 本机也已安装并验证真实服务（2026-11-07），不再仅依赖 C 的电脑结果。A 使用根目录 `start-ai.cmd` 打开18080端口，复用 `artifacts/classroom-demo` 内已有本机资料；不启用离线摘录提供方。详细证据及人脸待办见 [A真实AI验收](../verification/member-a-real-ai-2026-11-07.md)。Java17也会从标准 `Program Files/Java/jdk-*` 目录发现；项目 `artifacts/ffmpeg/bin` 存在完整二进制时仅向本次子进程添加PATH。其余机器仍须先安装依赖和模型。

需要单独排查文字AI时，可在项目专用Python环境运行 `tools/c-package/run-full.py --text-only --port 18080`，该模式明确关闭OCR/转写配置。复用旧课堂资料需显式传入 `--reuse-classroom-data artifacts/classroom-demo`；仅接受本项目artifacts下已有数据库目录，不创建替代旧库。

完成整合包构建后，在项目根目录双击 `start-c-full.cmd`。首次启动等待后台就绪，浏览器自动打开 `http://127.0.0.1:8088`。演示账号 `admin`、`hr`、`employee`，密码 `Lianzhi@2026`，仅用于本机合成资料。保持启动窗口打开；按 Ctrl+C 停止本次启动的服务。再次启动会保留 `artifacts/c-full-data/data` 中的数据。端口占用时明确停止，不接管或杀死其他程序。

该入口同时启动 Ollama、OCR/转写桥接和已验证的 Java 同源整合包。日志在 `artifacts/c-full-data/run-*`。服务只监听回环地址。媒体令牌每次启动随机生成并仅在子进程环境中传递；不写到仓库。模型和数据库不包含在基础 ZIP 内，基础 ZIP 可独立使用文字解析、报表及手工申请。

## 实际协议

问答使用 `LIANZHI_AI_BASE_URL` 和 `LIANZHI_AI_MODEL`，Ollama `/api/chat`；生成的是不可信 JSON，按[官方结构化输出协议](https://docs.ollama.com/capabilities/structured-outputs)使用JSON Schema约束语法，不能替代权限和来源校验。制度回答必须通过来源 ID、当前文档版本、可读权限和逐字引文复核。`LIANZHI_AI_TIMEOUT_SECONDS` 默认45秒，范围1～60秒；页面请求最多65秒。模型失败时不伪造答案。

向量使用 `LIANZHI_EMBEDDING_BASE_URL`、`LIANZHI_EMBEDDING_MODEL` 和人工维护的 `LIANZHI_EMBEDDING_REVISION`。Ollama `/api/embed` 批量最多32段，原始向量保存在 MySQL LONGTEXT 中，最多4096维。改变地址、模型或修订号会显示 STALE，重新解析后才恢复向量检索。模型同名换权重也应递增修订号；文字检索继续可用。

OCR 配置 `LIANZHI_OCR_URL/TOKEN`，转写配置 `LIANZHI_TRANSCRIPTION_URL/TOKEN`。POST JSON：

```json
{"fileName":"制度.png","base64":"文件内容的标准Base64"}
```

设置令牌时发送 `Authorization: Bearer ...`。响应 `sections` 为 `{source,text}` 数组。图片/PDF OCR 使用 `source={kind:"PAGE",ordinal:1,name:null,startMillis:null,endMillis:null}`。转写使用真实测量的 `TIME_RANGE`、`startMillis` 和 `endMillis`，不能返回自造页码或重叠/倒序时间。Java 适配器限制响应2MiB、正文160000码点和1000来源段；失败有明确状态。

本机桥接脚本 `tools/c-media/server.py` 使用真实 RapidOCR ONNX CPU 与 faster-whisper tiny CPU int8；不是固定结果服务。单次只处理一个请求，推理子进程55秒、768MiB上限；原文件10MiB。OCR图片最多1600万像素，扫描PDF最多20页。音视频最多120秒，FFprobe测量时长、FFmpeg解码，输出保留真实时间段。转写使用一般考勤领域词提示，不提供预期答案。识别有误字/漏字风险，引用时必须核对原图或原音频；混合图文PDF当前优先正文，只有无正文PDF才走OCR。

## 在另一台电脑安装

需要 Java17+、Python3.11、FFmpeg/ffprobe 和足够内存及磁盘。用 Python3.11 创建 `artifacts/c-media-venv`，在该环境安装 `tools/c-media/requirements.txt`。从官方来源安装FFmpeg并加入PATH。执行 `tools/c-package/install-ollama-cpu.py --help` 查看 CPU 运行时安装入口；下载脚本固定官方版本并验证HTTP区间、ZIP CRC，未声称验证完整大ZIP的SHA。

使用本项目专属 `OLLAMA_HOST=127.0.0.1:11439` 与 `OLLAMA_MODELS=<项目>/artifacts/local-ai/ollama-models` 启动运行时，下载 `qwen2.5:1.5b` 问答模型与 `qwen3-embedding:0.6b`。faster-whisper模型从 `Systran/faster-whisper-tiny` 下载到 `artifacts/local-ai/whisper-tiny`。运行 `python scripts/build-c-package.py` 构建并测试整合包，产生 `artifacts/c-package-latest.json`。然后使用根目录启动入口。不要将旧试验库直接放到修改过未发布V6的新包中；正式发布后不再修改迁移。

实现依据：[RapidOCR官方文档](https://rapidai.github.io/RapidOCRDocs/main/quickstart/)、[faster-whisper官方项目](https://github.com/SYSTRAN/faster-whisper)、[Ollama模型库](https://ollama.com/library/qwen3-embedding:0.6b)。本机运行结果与协议替身测试分开记录，见最终验收记录。
