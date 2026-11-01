# 手机HTTPS验收工具

用于B模块的隔离演示和真机准备。它启动自己的后端与Caddy HTTPS入口，提供同源静态页面和`/api`代理；不会修改C的生产部署方案、现有数据库或系统证书。已在Linux验证；Windows、macOS和实际手机仍需在目标设备验收。

## 准备软件与构建

需要Python3、JDK17+、Node.js和官方Caddy。Caddy官方安装说明：https://caddyserver.com/docs/install 。可通过`--caddy`指定程序路径，无需修改PATH。

在项目根目录先构建：

```bash
cd backend
./mvnw verify
cd ../frontend
npm ci
npm run build
cd ..
```

Windows的Maven命令使用`mvnw.cmd verify`；本工具的Windows运行结果尚未验证。

本次云端使用Caddy2.10.2，Linux程序从下面已校验的官方镜像提取，未安装系统服务或根证书：

```text
caddy:2.10.2@sha256:c3d7ee5d2b11f9dc54f947f68a734c84e9c9666c92c88a7f30b9cba5da182adb
```

## 先运行自动自检

```bash
python tools/mobile-acceptance/start.py --caddy /path/to/caddy --check
```

云端本次实际命令：

```bash
python tools/mobile-acceptance/start.py \
  --caddy /workspace/.local/bin/caddy \
  --run-dir /workspace/lianzhi-attendance/artifacts/mobile-https-20261028-storage \
  --check
```

默认仅监听127.0.0.1:8443；后端仅监听127.0.0.1:18080。脚本使用自己的本地CA验证证书链及主机名，检查SPA设备路由、Secure Cookie登录、CSRF和真实规则试算，成功写readiness.json后关闭自己启动的进程；不跳过TLS校验。重复运行保留本工具的证书及演示数据，不重置其他数据。

自检使用公开演示账号employee / Lianzhi@2026。如果你已在这个验收库中修改账号，请用新的`--run-dir`做首次自检，不覆盖已有库或重置密码。

## 同一局域网手机测试

1. 在**手机可以访问的电脑**运行工具。查询电脑的局域网IPv4，例如192.168.1.20；手机与电脑连接同一Wi-Fi。这个地址必须是真实分配给电脑的地址，不能照抄示例，也不能使用云工作区的内部回环地址。
2. 启动持续运行的验收服务（不要加`--check`）：

```bash
python tools/mobile-acceptance/start.py \
  --caddy /path/to/caddy \
  --ip 192.168.1.20 \
  --run-dir artifacts/mobile-phone
```

3. 如果电脑防火墙拦截，只为这个局域网测试入口允许TCP8443。不需要开放后端18080。不要把此演示入口直接公开到互联网。
4. 把工具输出的**公共根证书root.crt**传到测试手机。默认在`artifacts/mobile-phone/caddy-data/pki/authorities/local/root.crt`。仅传这一个公共证书，不复制存有私钥的整个目录。
5. Android在系统安全/凭据设置中为本次测试安装CA证书，具体入口因系统版本而异；iPhone安装证书描述文件后，还需在“设置→通用→关于本机→证书信任设置”中启用该测试根证书的完全信任。验收后移除测试证书。
6. 在手机浏览器访问`https://192.168.1.20:8443/attendance/device`（换成实际IP），确认没有证书错误再登录。不要通过忽略证书错误来代替可信HTTPS。手机不能直接打开电脑的127.0.0.1地址。
7. 先在“设备检查”逐项测试，再走实际打卡链路。Ctrl+C会停止工具自己启动的后端和HTTPS进程；数据保留在选定验收目录。

换IP或换验收目录后，证书与信任情况需要重新核对。云环境自动自检通过不代表手机已能访问；公网访问仍需受控服务器、真实域名及正式证书部署。

## 验收库与文件

- 后端强制使用独立H2文件库、demo配置和Secure Cookie，不读取现有MySQL业务库作为验收库。
- 验收目录包含自己的数据库、头像、日志、生成的Caddyfile、证书及readiness.json；Git忽略，不能提交真实员工资料、人脸图像或私钥。
- 服务启动前检查端口是否被占用；若地址或端口不可用，直接退出，不停止已有服务。
- 设备检查页不提交考勤、不登记人脸；定位只展示精度。摄像头仅本机预览，切页/撤回授权/切后台会释放设备。
- 二维码图片在本机识别。读取到内容不等于签名/有效期通过，真正打卡仍由后端校验。
- HTTPS自检也使用真实登录会话查询随机请求编号，验证只读回执接口；不创建打卡事件。断网后的结果恢复仍需按真机清单实际操作。

## 真机验收清单

在`docs/team/member-b-mobile-checklist.md`填写实际结果。当前没有真实手机结果，请保持“未执行”，不能把浏览器仿真定位、摄像头模拟或自动HTTPS请求当成真机通过。
