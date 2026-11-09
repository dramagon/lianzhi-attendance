# 联智员工移动端（uni-app）

独立Vue3 uni-app客户端，复用现有账号、服务端Session、逐次CSRF和考勤/人脸API。没有JWT、第二套用户表、客户端恒真人脸或自动补发打卡。

## 启动与构建

需要Node20.19+（支持Node20.19以上）及运行中的联智后端。主站和本目录分别安装依赖，锁文件不要混用。

```sh
cd mobile
npm ci
npm run dev:h5
# 本地地址 http://127.0.0.1:5175
npm run build:h5
npm run build:mp-weixin
npm test
npx playwright install chromium
npm run test:e2e
```

开发API代理默认本机8080，可通过`LIANZHI_DEV_BACKEND`指定已有后端地址；Windows PowerShell示例：`$env:LIANZHI_DEV_BACKEND='http://127.0.0.1:18080'`后运行dev:h5。这是构建/开发配置，不包含真实身份、密钥或固定机器地址。服务端需先正常启动；移动端不会创建公司、员工或排班。测试中的管理员与合成员工仅用于独立演示测试库，不用于真实数据。

H5产物：`dist/build/h5/`。生产部署为静态站点，使用可信HTTPS，并将同源`/api`反向代理到主应用；默认`VITE_API_BASE_URL`留空。不需要为移动端放宽后端CORS或关闭CSRF。Vite开发服务默认仅监听127.0.0.1；手机现场请使用正式HTTPS静态服务，不将开发服务器充当交付部署。若用独立API域名，须配置合法Origin、Secure/SameSite及浏览器第三方Cookie策略，不能靠客户端修改HttpOnly Cookie绕过。

微信小程序产物：`dist/build/mp-weixin/`。构建前在本地`src/manifest.json`填写实际`mp-weixin.appid`，在`.env.local`配置`VITE_API_BASE_URL=https://你的合法后端域名`（不含`/api`）；在微信后台配置request合法域名及定位隐私用途，并重新构建。导入微信开发者工具后逐项验收。本仓库不包含真实AppID、域名、授权或设备配置；默认空AppID的构建仅证明可编译，不能直接当作已上线小程序。

## 功能与平台差异

- 登录/退出、本人档案、授权场所、上下班、分页原始记录和带审批来源的日结果。
- H5使用本地拍照/二维码图片和jsQR实际解码；微信小程序编译为原生uni.scanCode。摄像头实时扫码、系统拍照及权限效果须分别在真机确认，H5图片解码不代替原生摄像头扫描验收。
- GPS明确请求WGS84，客户端检查60秒新鲜度和100米以内精度；服务端继续校验坐标、场所范围、排班及实际时间。SDK/后台服务或网络不可用均不伪装通过。
- 人脸登记、更新、删除、可选/强制验证；只有后端返回有效凭据才能提交所选人脸打卡。验证失败/过期不静默取消人脸。更新用带expectedRevision的multipart PUT，原生端以ArrayBuffer请求发送，避免uni.uploadFile仅支持POST造成方法丢失。没有变更后端API。
- 采集需明确同意。照片不写业务存储；H5临时Blob地址在撤回同意、切页/后台及提交后释放，上传缓冲清零。小程序使用系统临时图像，撤回/切页清除引用，不保存到相册或持久文件。客户端不计算“本人”分数，后端真实活体和版本约束保留。
- 结果未知时仅保存当前账号的requestId/type/userId，不保存二维码、坐标、照片、密码或Cookie。刷新/重新登录后可查原回执，未查到不等于原请求被取消。原始记录可继续核对；明确放弃只清理本地编号，不删后台数据、不自动重发。
- 另一客户端可输入原请求编号“跨端回执核对”，服务器仍按当前账号查询；不会转交人脸凭据或补发打卡。两个独立浏览器的此流程已自动验证。
- H5 Cookie由浏览器管理；小程序JSESSIONID/SESSION仅存运行内存，处理登录轮换，每次写入读取新CSRF。小程序进程退出后须重新登录，持久待核对编号按重新登录的本人ID隔离。旧会话迟到401不能清掉新登录。

## 可复现测试与边界

测试要求隔离的演示后端（含公开演示管理员）在8080，禁止指向实际员工库。首次可按主项目说明启动空H2 demo。测试自动创建自己的合成员工、场所和当天排班，扫码图为实际生成的短时二维码，定位由浏览器明确仿真。可用`PLAYWRIGHT_CHROMIUM_EXECUTABLE`指定已安装Chromium路径。

H5浏览器覆盖真实上下班/记录、二维码图片解码、权限拒绝与恢复、响应丢失、刷新和跨浏览器只读回执；另外两项人脸页面测试使用明确SDK接口替身，验证二进制、版本、授权、失败/过期，不代表真实人脸或活体已通过。协议单测覆盖原生Cookie/CSRF轮换、会话隔离、禁止自动重试、最小化持久存储和multipart。

云端未运行Windows授权SDK、微信开发者工具/微信真机或真实手机GPS/摄像头；这些项目必须在A环境执行。具体清单见`../docs/mobile-checklist.md`。Windows环境可从仓库根目录运行`python scripts/check-face-environment.py --output artifacts/face-preflight.json`做静态检查；该检查不激活SDK，不显示密钥，也不证明本人/异人或防照片攻击已通过。

## 来源和依赖

官方模板：https://github.com/dcloudio/uni-preset-vue/tree/vite ，读取提交`f42e736569c0c51ceb13a69d2a2122b382fbe9f1`。DCloud包统一固定`3.0.0-5030420261029002`；Vue和编译器按该官方模板兼容版本锁定。插件peer明确要求Vite5.2.8，因此未强行换主站Vite或忽略peer约束。升级须按官方版本成组验证。

官方API参考及安装types中的对应声明：

- https://uniapp.dcloud.net.cn/api/request/request.html
- https://uniapp.dcloud.net.cn/api/location/location.html
- https://uniapp.dcloud.net.cn/api/system/barcode.html
- https://uniapp.dcloud.net.cn/api/media/image.html
- https://uniapp.dcloud.net.cn/tutorial/platform.html

构建包另附acceptance.md和tools/check-face-environment.py，SDK静态检查可在解压目录运行`python tools/check-face-environment.py --output face-preflight.json`；真实环境变量仍只配置在本机。包中的AppID/合法域名未实际配置，微信端须按上述源码步骤配置重建。

手机H5同源HTTPS示例见Caddyfile.example：将示例主机名/路径/端口替换为实际值，按已有tools/mobile-acceptance说明安装并运行Caddy，手机与电脑可达且明确信任内部CA后访问。/api先代理至原Windows后端，其余请求读取H5静态文件，不改登录/CSRF。不要自动导入CA或关闭证书检查；此示例不意味着已验证手机证书信任。微信生产request域名仍须满足微信平台要求，不能用局域网自签名开发结果代替。

## B配合验收工具与恢复加固

登录后的“设备验收”页可读本机基本信息、填写实测SHA及逐项结果，并导出JSON；微信端在用户点击后写入本地并尝试系统文件分享，取消分享只报告本地生成。服务不通或尚未登录时，可点“离线填写设备验收”。同一账号切换页签保留当前草稿，退出或刷新不承诺保留，及时导出。报告不发往后端，不自动填写通过；未执行默认NOT_RUN。设备观测只是本端最近状态，不证明当前证书、GPS、SDK或业务均已通过。真实含凭据打卡标通过时须填原始事件编号，由操作人员核对。

采集现在可明确取消；换页、撤回同意或换账号会中止H5选择器并拒绝迟到结果。系统拍照可能临时切出应用：只有同一采集请求返回同账号/同页面、且同意仍有效时才接收临时图像，依旧不自动上传。原定位和凭据在后台立即失效，返回后须重新定位和验证。H5 pagehide/visibilitychange也会清理本地状态；迟到的识别成功不能恢复凭据。登录/会话恢复一确认账号即恢复待核对编号，不再等待档案接口成功。

未提交源码的构建包现在命名为`lianzhi-mobile-<SHA>-working.zip`，清洁提交才使用正式`lianzhi-mobile-<SHA>.zip`，防止预览构建覆盖已交付版本。仓库根目录运行`python scripts/test-mobile-package.py`验证该隔离规则、校验值和私有开发配置排除。

微信构建包会校验usingComponents的本地js/json/wxml依赖，并包含components目录。构建通过不代表ZIP必然完整；打包器发现缺失引用会直接拒绝，私有开发配置、源码映射仍排除。
