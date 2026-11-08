# B云端移动客户端及设备交付

## 实现

在B1整合基线之后新增独立mobile目录，使用DCloud官方uni-preset-vue的vite模板f42e736569c0c51ceb13a69d2a2122b382fbe9f1，固定同一DCloud版本及完整lockfile。插件peer固定要求Vite5.2.8，不使用force/legacy-peer-deps，也未替换主站依赖。H5及mp-weixin分别真实构建，不将H5网页套壳称作原生能力通过。

复用现有Session/CSRF及本人API：登录/退出/本人档案、授权场所、WGS84定位、H5二维码图片实际解码/微信原生扫码、上下班打卡、本人原始事件分页和日结果、登记/更新/删除及可选/强制人脸验证。人脸只信任后端当前凭据，失败或过期不退回普通成功。原生更新用ArrayBuffer multipart PUT，避免仅POST的uploadFile丢失方法。没有新增后端API、用户表或迁移。

未知打卡结果只存当前用户ID、requestId及方向；跨刷新恢复和另一独立浏览器的同账号手工回执核对均不会重发。原生Cookie仅内存轮换，H5由浏览器管理HttpOnly Cookie；每次写入刷新CSRF。旧会话迟到401不能清掉新登录。页面切换/隐藏清除定位和凭据，照片不持久化，H5由自有选择器管理Blob并及时释放，提交后缓冲清零。

设备脚本check-face-environment.py仅离线检查Windows x64、Java17x64、ArcFace类、3个DLL的PE/x64架构、配置存在性、密钥/阈值格式；无网络激活、无密钥输出。云端Linux实跑ready=false、真实SDK/真人/活体/人脸打卡均NOT_RUN。现场清单补充原生平台与反例，不抹掉A已有本人验证反馈。

## 本轮结果

- 主站整合基线：后端365项（364执行通过、1项Windows真SDK条件跳过），完整主站浏览器52项通过，主站format/build通过；证据见member-b-cloud-baseline.md。本移动阶段没有改变主站业务代码。
- 移动端H5构建通过，微信小程序构建通过。空AppID/域名不代表微信发布或真实手机已运行。
- 移动协议8项通过：Cookie/CSRF轮换、H5 Cookie管理、403/传输错误不重放、会话到期及旧响应隔离、最小化持久数据、定位/QR/凭据边界、二进制multipart。
- H5实际浏览器5项通过：真实后端登录、短时二维码图片/jsQR、仿真GPS、上下班及本人日结果；真实写入后丢响应、刷新及独立浏览器查原回执，事件仍1条；定位拒绝/恢复。另两项使用明确SDK页面接口替身验证POST/PUT/DELETE、CSRF/版本、Blob释放和失败/过期不打卡。不能把后两项当作真实SDK识别证据。
- SDK静态检查器4项测试通过，包含合成PE架构、非法密钥/分数与报告不泄漏测试秘密值；不是SDK执行。
- Python检查器/打包器编译、git diff --check通过。打包器仅包含两个已构建目标、公共说明与静态检查器，排除node_modules、dotenv、源映射、运行库、测试图片及私有微信开发配置；manifest记录Git和实际未执行范围。

最终日志：/tmp/b-cloud-b1-backend-final.log、/tmp/b-cloud-b1-e2e.log、/tmp/b-mobile-h5-final.log、/tmp/b-mobile-mp-final.log、/tmp/b-mobile-unit-final.log、/tmp/b-mobile-e2e-release.log、/tmp/b-cloud-face-preflight.log。H5截图artifacts/b-mobile-h5-success.png，静态报告artifacts/b-cloud-face-preflight.json。首次编译受到网卡枚举沙箱限制，按工具允许的执行环境重跑成功，未修改编译器；早期UI测试误用uni-input/默认英文确认框定位，修正实际控件与中文文案后真实业务断言保留。

## 仍须现场确认

A已有Java17、Windows x64 ArcFace3.0授权，不重做激活或要求拷贝密钥到云端。按mobile/README.md配置合法HTTPS、微信AppID/域名后，在微信开发者工具和实际手机核对原生会话/授权/扫码/GPS/二进制上传。真实同人/异人、照片/屏幕、多人、低光、RGB活体与含凭据打卡落库逐项记结果；本轮不提供这些新现场通过结论。正式MySQL/Redis/Nacos/Feign与部署由C/A继续协调，客户端未自行放宽公共认证或拆服务。

源分支codex/member-b-cloud-finish，PR目标codex/member-a-release-validation。B旧分支185c2e0保留不覆盖，main由A最终审查合并。构建包及远端SHA以最终汇报为准。
