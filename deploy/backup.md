# MySQL 备份与恢复

准备与服务端兼容的 MySQL 客户端，在本机受限目录保存含 `[client]` 的配置文件，填写 host、port、user、password。连接远端时沿用正式 TLS 配置。备份与恢复账户分别授予读取源库和创建、写入恢复库所需权限。

```powershell
python scripts/c-mysql-backup.py backup --client-config path/to/mysql.cnf --database lianzhi --file artifacts/lianzhi-backup.sql
python scripts/c-mysql-backup.py restore --client-config path/to/mysql.cnf --database lianzhi_recovery --file artifacts/lianzhi-backup.sql
```

暂停写入与迁移后备份，并在同一维护窗口备份头像目录。SQL 包含业务记录和库内二进制数据，需限制访问。只恢复可信备份，目标库须为新库。先用隔离应用核对迁移历史、考勤、审批、报表与知识引用，再决定切换。恢复中断时先检查新库，保留原库。Redis 会话独立管理，恢复后的应用重新登录。

Windows 独立 MySQL 8.0 实测中文及二进制数据往返，并确认拒绝覆盖已有备份文件和目标库。正式库和头像目录仍需在实际部署环境联合演练。
