# 人脸SDK配置

服务端使用虹软ArcFace Java Windows x64 V3.0适配器，提供人脸检测、质量与姿态检查、RGB活体、特征提取和比对。需要Windows x64、64位JDK 17、匹配的官方SDK及对应授权。

## 配置

配置示例见 [face-config.env.example](face-config.env.example)。主要环境变量：

| 变量 | 用途 |
| --- | --- |
| LIANZHI_ARCSOFT_ENABLED | 启用虹软适配器 |
| LIANZHI_ARCSOFT_JAR | 官方Java SDK JAR路径 |
| LIANZHI_ARCSOFT_NATIVE_DIRECTORY | 对应Windows x64 DLL目录 |
| LIANZHI_ARCSOFT_APP_ID / LIANZHI_ARCSOFT_SDK_KEY | 官方应用授权 |
| LIANZHI_FACE_ENABLED | 启用人脸业务 |
| LIANZHI_FACE_ENCRYPTION_KEY | 稳定保存的32字节Base64加密密钥 |
| LIANZHI_FACE_MINIMUM_SCORE | 身份比对阈值，取值(0,1)，默认未配置关闭 |
| LIANZHI_ARCSOFT_RGB_LIVENESS_THRESHOLD | RGB活体阈值，默认0.8 |
| LIANZHI_FACE_REQUIRED | 是否对打卡强制要求验证凭据，默认false |

本机完整启动入口可从 `artifacts/arcsoft-credentials.local.ps1` 读取私有配置。SDK、DLL、授权、设备激活缓存和密钥均不提交仓库；不在其他系统上直接使用Windows运行库。阈值应按实际设备与使用条件确定。

## 使用

1. 登录本人账号，进入人脸登记页，阅读采集说明并主动授权摄像头。
2. 采集单人正脸，服务端检测、活体及质量检查通过后保存加密特征。
3. 打卡时完成二维码与定位，选择人脸验证；后端验证挑战、身份及比对结果，生成一次性凭据。
4. 提交打卡时再次校验并消费该凭据。更新或删除登记会撤销旧版本的未消费凭据。

个人头像与人脸登记分别管理，上传头像不能代替人脸登记。SDK或授权不可用时明确关闭识别，不接受客户端声明的“识别成功”。原始图像不作为业务资料持久化，特征使用AES-256-GCM加密；备份与恢复必须同时保护数据库和对应加密密钥。更换密钥前需制定迁移或重新登记方案。

## 设备与运行检查

摄像头使用可信HTTPS或本机安全上下文；切页、退出或撤回同意时释放设备。可使用 [移动设备清单](../mobile-checklist.md) 核对权限、本人/异人、照片/屏幕及凭据失效等场景。

`python scripts/test-attendance-mysql.py --face-workflow` 在独占数据库验证业务流程，使用明确测试替身，不代表真实SDK准确率或活体效果。真实效果需在已授权设备上检查。
