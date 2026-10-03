# 源码公开与发布

自 v0.6.32 起，权利人有权授权的原创代码、训练代码、文档和二次元小猫头像使用源码可用许可，禁止未经许可售卖，见 `LICENSE` 与 `NOTICE`。分发时必须保留作者、官方源码链接和软件内的源码/许可入口。该许可不是 OSI 定义的标准开源许可。已发布的 Apache-2.0 旧版继续适用原授权，文本保存在 `LICENSES/Apache-2.0.txt`。

第三方依赖保留各自许可，LGPL 组件的对应源码归档随依赖提供。没有代码混淆、加壳或源码加密；许可义务和 APK 签名不等于无法修改源码或绝对防倒卖。

公开仓库：`https://github.com/xudd2025-collab/SplashSkip`。项目由所有者独立维护，说明见 [MAINTAINERS.md](MAINTAINERS.md)。

## 仓库内容

源码归档包含 Android 源码、图标、裁剪后的界面特征、模型、训练/构建/检查脚本、第三方运行库及许可，以及 GitHub Actions 配置。签名私钥、原始个人截图、诊断日志、配对密钥和本机 SDK 路径不进入归档；`.gitignore` 也排除这些内容。

训练代码公开，原始个人数据不公开，因此没有原始数据时不能复现当前模型的完全相同权重。可以按 `tools/model_training.md` 使用自行采集、获准使用且已脱敏的样本训练。

## 固定更新源

官方 APK 的更新源由 `UpdateChecker.OFFICIAL_REPOSITORY` 固定为 `xudd2025-collab/SplashSkip`。应用忽略并清除旧版自定义源，不再提供输入入口。只读取公开正式 GitHub Releases，不读取 Git 提交或私有仓库，不要求手机填写账号令牌。

稳定标签例如 `v0.6.32`，安装包文件名例如 `SplashSkip-v0.6.32.apk`。仓库未公开或没有正式 Release 时，应用会明确显示不可访问/尚无版本，不编造更新结果。

## 构建与签名

- `version.properties` 是 APK 和 Gradle 的共同版本来源。
- `Build and checks` 工作流生成验证 APK，不用于正式覆盖更新。
- `Signed release` 工作流手动运行，默认仅构建固定签名安装包；勾选 `publish` 才发布。版本标签须存在且指向构建提交。
- 发布需要仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_PASSWORD`、`KEY_ALIAS`，由发布负责人通过私密渠道配置。源码和 PR 不包含密钥。建议为 `release` environment 设置审查。
- 已安装原型只能由相同签名覆盖。本地保留现有签名；如换用新密钥，需要重新安装。不要公开当前私钥。
- 工作流使用 JDK 17、SDK 36。云端结果以仓库 Actions 页面为准。

固定签名构建示例：

```powershell
./build.ps1 -SdkPath C:\path\android-sdk -JavaHome C:\path\jdk-17 -SigningKey C:\private\release.keystore -KeyAlias release
```

密码通过 `SPLASHSKIP_STORE_PASSWORD` 与 `SPLASHSKIP_KEY_PASSWORD` 环境变量提供，不写进源码或命令。

源码归档：

```powershell
python ./tools/package_source.py ../SplashSkip-source-v0.6.32.zip
```
