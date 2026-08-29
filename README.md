# HyperOS4NotificationImportance

一个仅用于 HyperOS 4 的轻量 LSPosed 模块，用来恢复系统设置中被隐藏的通知渠道“通知重要性”选项，并补充“最低（不显示状态栏图标）”等级。

## 使用方法

1. 从 GitHub Releases 下载并安装 release APK。
2. 在 LSPosed 中启用模块。
3. 作用域同时勾选“设置”（`com.android.settings`）和“系统界面”（`com.android.systemui`）。
4. 重启手机。
5. 打开“设置 → 通知与状态栏 → 应用通知管理 → 应用 → 具体通知类别”，调整通知重要性。
6. 选择“低”后，通知卡片仍会保留，低优先级通知的状态栏图标会由 System UI 过滤。

## 构建

仓库每次推送都会通过 GitHub Actions 构建 debug 和使用固定密钥签名的 release APK。Actions 需要配置 `RELEASE_KEYSTORE_BASE64` 和 `RELEASE_SIGNING_PASSWORD` 两个仓库 Secret。

也可以在本地使用 JDK 17 与 Gradle 8.11.1。若要生成已签名 release APK，需设置 `RELEASE_STORE_FILE` 与 `RELEASE_SIGNING_PASSWORD` 环境变量：

```bash
gradle assembleDebug assembleRelease
```

## 兼容性

- 目标系统：Android 17 / HyperOS 4
- 框架：LSPosed（兼容传统 Xposed API 82）
- 作用域：`com.android.settings`、`com.android.systemui`

不同系统版本可能调整内部类名。若选项仍未出现，请在 LSPosed 中导出模块日志并提交 Issue。

## 隐私

模块不申请任何权限，不联网，也不收集数据。

## 许可证与致谢

本项目采用 GPL-3.0-or-later。通知重要性恢复思路参考了 GPL 项目 [Pengeek / CustoMIUIzer](https://github.com/monwf/customiuizer)。
