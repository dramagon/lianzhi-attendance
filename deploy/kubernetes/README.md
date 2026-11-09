# Kubernetes单实例部署清单

`app.yaml`提供backend/web Service与Deployment、头像PVC及解析临时目录；真实MySQL由部署方提供。默认镜像名为`lianzhi-backend:local`和`lianzhi-web:local`，实际使用时在本机替换为已构建且集群可拉取的镜像。

先构建两个Dockerfile镜像。先创建lianzhi命名空间，再在集群中创建`lianzhi-runtime` Secret，至少设置`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`ADMIN_INITIAL_PASSWORD`；可信TLS入口下设置`SESSION_COOKIE_SECURE=true`。模型/OCR等变量可放入同一Secret，见上级.env.example。不要把实际Secret YAML或密码提交Git，也不要把`kubectl get secret -o yaml`输出贴到聊天。

可先运行`kubectl apply --dry-run=server -f deploy/kubernetes/app.yaml`校验，再部署；通过Ingress或`kubectl -n lianzhi port-forward service/web 8088:80`进入同源web。先检查`/api/system/info`及真实登录/CSRF/上传/检索，不把Nginx存活当作MySQL/AI可用。

backend为**单副本Recreate**：当前会话及申请确认草稿在内存中，不支持直接扩成多副本。后台知识任务用数据库锁及持久租约，异常中断后等待显式重试。数据库文档保存原文件、切片、版本和范围；头像需要PVC。部署后必须验证PVC、数据库备份与恢复、应用重启及访问隔离。

GitHub Actions 的 C 容器集成工作流新增 kind 集群验收。它使用两张本次构建的镜像、独立 MySQL 和随机测试密码，通过同源 Web 验证登录、CSRF、文档索引与引用、报表 CSV、后端重建后的数据库记录和头像卷写入。运行结果以该次流水线为准。也可在新建的空 kind 集群执行 `python scripts/test-c-kubernetes.py --cluster <名称>`。

此流程使用测试数据库，验证后端 Pod 重建，正式集群、数据库备份恢复及集群内 Redis、Nacos、模型和公网 TLS 仍需按实际环境验收。
