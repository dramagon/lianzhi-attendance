# 向量服务注册与调用

主应用默认直连 Ollama。需要拆分时，单独启动向量工作进程，工作进程用 Nacos Java SDK 2.5.2 注册，主应用按模型和修订号发现健康实例，再用 OpenFeign 13.6 调用。工作进程只接受文本向量请求，数据库、文档权限、引用和申请操作由主应用管理。

两边配置 `LIANZHI_NACOS_ADDRESS`、`LIANZHI_EMBEDDING_MODEL` 和 `LIANZHI_EMBEDDING_WORKER_TOKEN`。启用 Nacos 认证时配置 `LIANZHI_NACOS_USERNAME`、`LIANZHI_NACOS_PASSWORD`。可选 `LIANZHI_NACOS_NAMESPACE`、`LIANZHI_NACOS_GROUP`（默认 DEFAULT_GROUP）、`LIANZHI_EMBEDDING_REVISION`（默认 1）两边须一致。服务令牌须为 32–256 位字母、数字、下划线或连字符。

工作进程配置 `LIANZHI_EMBEDDING_BASE_URL` 指向自己的 Ollama。使用打包后的后端 JAR 启动：

```text
java -Dloader.main=cn.lianzhi.attendance.knowledge.provider.EmbeddingWorker -cp backend/target/lianzhi-backend-0.1.0.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

工作进程默认监听并注册 `127.0.0.1:8092`。跨机配置 `LIANZHI_EMBEDDING_BIND_HOST`、`LIANZHI_EMBEDDING_ADVERTISE_HOST`、`LIANZHI_EMBEDDING_WORKER_PORT`，注册地址须能从主应用网络访问。主应用设置 `LIANZHI_EMBEDDING_DISCOVERY=true` 后按原方式启动。Nacos 需开放配置端口及其加 1000 的客户端 gRPC 端口。工作进程的 HTTP 接口仅用于本机或受控私网。

Feign 连接超时 2 秒，读取超时 25 秒，关闭重试与重定向。实例不可用时返回向量失败，保留文本检索。切换直连与发现模式后重建向量索引，模型更新时同步递增修订号。

验收时设置 `LIANZHI_C_NACOS_IT=true`，在 backend 执行 `mvnw.cmd -Dtest=NacosEmbeddingIT test`（Linux 使用 `./mvnw`）。需真实 Nacos 和已安装的 Ollama 模型。测试启动独立工作 JVM，验证无实例、认证失败、修订号不匹配、真实向量生成、实例停机及重启恢复。
