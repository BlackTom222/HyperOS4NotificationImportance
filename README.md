# HyperOS4NotificationImportance

一个仅用于 HyperOS 4 的轻量 LSPosed 模块，用来恢复系统设置中被隐藏的通知渠道“通知重要性”选项，并隐藏低优先级通知的状态栏图标。

## 使用方法

1. 从 GitHub Releases 下载并安装 release APK。
2. 在 LSPosed 中启用模块。
3. 作用域同时勾选“设置”（`com.android.settings`）和“系统界面”（`com.android.systemui`）。
4. 重启手机。
5. 打开“设置 → 通知与状态栏 → 应用通知管理 → 应用 → 具体通知类别”，调整通知重要性。
6. HyperOS 四档对应关系为“紧急(4) / 高(3) / 中(2) / 低(1)”。选择“低”后，通知卡片仍会保留，但状态栏图标会由 System UI 过滤；“中”及以上保持显示图标。

v1.2.1 使用现代 libxposed API 102，在 System UI 创建图标前接管 `NotificationIconsInteractor` / `StackCoordinator`。模块会先允许静默通知进入状态栏图标候选列表，再根据最终 Ranking 精确判断：仅排除 HyperOS 界面“低”对应的 `MIN(1)`，保留界面“中”对应的 `LOW(2)`。同时接管新版 `StatusBarNotificationIconsInteractor` 的图标来源；不修改通知通道的实际重要性，不会额外开启声音或悬浮通知。

模块应用界面使用 `fan.miuix` 组件库，采用简洁单页布局：首页集中展示激活状态、两个作用域和通知规则，移除无实际功能的顶部图标、底部导航及装饰性大图。页面可正常上下滑动，但不显示滚动条；卡片高度随内容自适应，并支持深色模式。

点击“展开模块信息与高级操作”可查看 Android、模块、框架和 API 版本以及运行中的目标进程，也可使用“重启作用域”。重启前会提示影响范围，确认后请求 Root 权限，停止“设置”和“系统界面”进程；系统界面由系统重新拉起，设置可手动重新打开以加载新 Hook。Root 不用于其他操作。未连接框架时，作用域显示“待检测”，不将无法读取的状态误报为“未启用”。

## 构建

仓库每次推送都会通过 GitHub Actions 构建 debug 和使用固定密钥签名的 release APK。Actions 需要配置 `RELEASE_KEYSTORE_BASE64` 和 `RELEASE_SIGNING_PASSWORD` 两个仓库 Secret。

本地构建与 Actions 保持一致，使用 JDK 21、Gradle 9.5.1 和 Android SDK 37。`fan.miuix` 依赖还需要配置具有对应 GitHub Packages 读取权限的 `MIUIX_MAVEN_USER` 与 `MIUIX_MAVEN_TOKEN` 环境变量；不要把凭据写入源码。若要生成已签名 release APK，需设置 `RELEASE_STORE_FILE` 与 `RELEASE_SIGNING_PASSWORD` 环境变量：

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
