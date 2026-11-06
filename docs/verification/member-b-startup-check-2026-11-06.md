# B展示前启动自检与进度核对（2026-11-06）

## 进度核对

本轮开始时，本地及远端B分支均为8b12018，C完成分支为9ceee3a，main为67969ac，主分支仍待A合并。使用git ls-remote核对实际远端SHA，未将本地旧tracking ref当作远端最新状态。

代码已有整站演示、C报表/知识库/助手整合，roadmap仍把报表等列为“待开发”。现按B整合分支实际情况更新状态，并保留真实SDK、手机、Windows现场、外部AI服务与小程序的未验收边界，没有将演示通过改写成所有课程要求均已完成。

## 本轮完成

- Windows新增check-demo.cmd，调用启动器CheckOnly模式。自检验证整包文件SHA-256、Java17+、18080端口及目录写入；不启动业务服务或创建演示数据库。
- Windows启动前同样检查。优先使用本目录runtime、其次JAVA_HOME、最后PATH。Java低于17时提前明确报错；Java版本检测使用ProcessStartInfo读取stderr，避免Windows PowerShell把正常java -version输出作为错误中断。
- 提供`-CheckOnly -NoPause`自动化调用，成功退出0、失败退出1；cmd传递失败码。正常启动等待上限改为120秒，可通过StartupTimeoutSeconds在15至300秒间配置。浏览器打开失败时保留服务并提示手动地址。
- Linux/macOS增加`sh start-demo.sh --check`，验证文件、Java和目录写入；端口由实际启动验证。校验失败/缺文件/旧Java不启动后端，不删除demo-data。
- ZIP增加check-demo.cmd及checksums.sha256，共8个白名单文件。manifest.fileHashes记录6个程序/脚本/说明的实际SHA-256；Windows脚本保留CRLF，嵌套依赖仍使用原有STORED压缩模式。校验用于发现意外损坏，并非数字签名。
- 运行和进度说明同步更新。自检通过不等同于完整业务通过，仍需运行“一键整站演示”。

## 实际验证

`python3 scripts/test-classroom-package.py`：7项通过。覆盖归档白名单/无运行数据/实际SHA/CRLF/嵌套JAR格式、带空格目录和Java路径、不启动业务且不建数据库、缺文件、损坏JAR、Java8和无效版本、过时构建产物拒绝。Java版本负例使用明确的可执行脚本替身，不代表真实旧版JVM实测。日志：/tmp/b-preflight-tests.log。

实际JAR展示包在本机Java21上自检通过，随后使用start-demo.sh启动。9项完整课堂浏览器验收通过，包含DOCX真实解析、报表CSV、员工来源引用、失败停止、暂停及进度恢复基础用例。日志：/tmp/b-preflight-server.log、/tmp/b-preflight-e2e.log。实际换进程恢复未在本轮重跑，沿用上轮已记录证据，不将基础恢复用例计作新一次实际重启验收。

Python脚本编译、sh语法和git diff --check通过。没有修改后端业务、前端页面或迁移，本轮不重新宣称运行346项后端、46项普通浏览器或MySQL全量回归；历史证据见2026-11-04整站记录。

Windows PowerShell/Windows端口负例未在云端执行，仍须实际Windows运行check-demo.cmd及start-demo.cmd。官方Azul/Adoptium运行时元数据下载仍遭代理403拒绝，本次不捆绑Windows JRE；可用本地runtime或Java17+安装。本地包不依赖SDK授权或外部模型。

## 交付

源码、检查脚本与证据随B分支交付；最终压缩包为artifacts/classroom-demo.zip及带日期/提交号的副本，不提交运行数据。Windows完整解压后先检查再启动。main不自动合并；PR和远端同步结果以本轮最终汇报为准。
