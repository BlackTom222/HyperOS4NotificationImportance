# HyperOS4NotificationImportance

一个仅用于 HyperOS 4 的轻量 LSPosed 模块，用来恢复系统设置中被隐藏的通知渠道“通知重要性”选项，并隐藏低优先级通知的状态栏图标。

## 使用方法

1. 从 GitHub Releases 下载并安装 release APK。
2. 在 LSPosed 中启用模块。
3. 作用域同时勾选“设置”（`com.android.settings`）和“系统界面”（`com.android.systemui`）。
4. 重启手机。
5. 打开“设置 → 通知与状态栏 → 应用通知管理 → 应用 → 具体通知类别”，调整通知重要性。
6. 选择“低”或“最低”后，通知卡片仍会保留，低优先级通知的状态栏图标会由 System UI 过滤；“中”及以上保持显示图标。

v1.1.0 使用现代 libxposed API 102，在 System UI 创建图标前接管 `NotificationIconsInteractor` / `StackCoordinator`。只有最终 Ranking 为 `LOW(2)` 或 `MIN(1)` 的通知会被排除；不再全局关闭“静默通知图标”，因此 `DEFAULT(3)`（界面显示为“中”）不会再被误隐藏。通知卡片和前台服务保持不变，旧版 `NotificationIconAreaController` 仅作为兼容回退。

模块应用首页通过 libxposed Service 显示激活状态、框架版本、API 版本、作用域以及运行中的目标进程。点击“授予 Root 权限并重启作用域”会请求一次 Root 权限，随后停止“设置”和“系统界面”进程，由系统自动重新拉起并加载新 Hook；Root 不用于其他操作。

## 构建

仓库每次推送都会通过 GitHub Actions 构建 debug 和使用固定密钥签名的 release APK。Actions 需要配置 `RELEASE_KEYSTORE_BASE64` 和 `RELEASE_SIGNING_PASSWORD` 两个仓库 Secret。

也可以在本地使用 JDK 17 与 Gradle 8.11.1。若要生成已签名 release APK，需设置 `RELEASE_STORE_FILE` 与 `RELEASE_SIGNING_PASSWORD` 环境变量：

```bash
gradle assembleDebug assembleRelease
```

## 兼容性

- 目标系统：Android 17 / HyperOS 4
- 框架：LSPosed 2.1.1 或其他完整支持现代 libxposed API 102 的实现
- 作用域：`com.android.settings`、`com.android.systemui`

不同系统版本可能调整内部类名。若选项仍未出现，请在 LSPosed 中导出模块日志并提交 Issue。

## 隐私

模块不联网，也不收集数据。Root 权限仅在用户点击重启按钮时通过 `su` 请求，用于重启两个固定作用域。

## 许可证与致谢

本项目采用 GPL-3.0-or-later。通知重要性与 System UI 过滤思路参考了 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) 和 GPL 项目 [Pengeek / CustoMIUIzer](https://github.com/monwf/customiuizer)。
