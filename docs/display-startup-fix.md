# 先连接眼镜后播放失败：原因与修复

## 已确认的故障位置

你反馈先连接眼镜时，首页正常显示触控板，打开视频后出现“无法输出到眼镜，已改为手机播放”，而先在手机播放再连接眼镜可以正常输出。

这个提示来自播放器创建外接屏窗口失败后的异常处理。因此可以确定：应用已经识别眼镜，但播放器的外接屏窗口没有成功显示，随后执行了退回手机的分支。陀螺仪数据走独立的传感器读取流程，不能代表视频窗口已经创建成功。

两种连接顺序经过的页面流程不同：

- 先连接眼镜：媒体库已经有一个眼镜窗口，点击视频后需要将输出交给播放器。
- 先播放再连接：播放器从手机输出切换到眼镜，没有正在显示的媒体库眼镜窗口需要交接。

检查代码发现，旧版媒体库的眼镜窗口只在页面销毁时关闭，页面暂停、进入播放器时仍被保留；播放器又在 `onCreate` 中立即请求眼镜窗口。首次显示失败后，播放器直接退回手机，同一个外接屏 ID 和分辨率没有变化时，显示监听也不会重新创建播放器。

这些是代码中已确认的生命周期与恢复缺口，能解释连接顺序为何影响结果。实际手机上是哪个系统窗口条件拒绝了输出，还需要异常堆栈确认，不能仅凭现象断定是某一个 ColorOS 设置。

Android 官方的 Presentation 示例会在页面暂停时关闭外接窗口、恢复时重新显示；文档也明确说明，系统可能拒绝窗口，即使外接显示器仍然存在。[Android Presentation 文档](https://developer.android.com/reference/android/app/Presentation)

## 本次修复

- 媒体库、浏览器和播放器统一使用 `GlassesPresentation` 管理眼镜窗口。
- 页面暂停时主动关闭自己的眼镜窗口，给下一页释放输出窗口；返回时重新显示。
- 等页面恢复到前台、主线程处理完当前生命周期回调后再显示眼镜窗口。
- 对 `InvalidDisplayException` 和 `BadTokenException` 最多尝试三次，间隔为 400 毫秒、800 毫秒。持续失败才退回手机，其他异常直接执行失败处理。
- 主动交接窗口不会触发“眼镜断开”的恢复逻辑；系统意外取消窗口仍由原来的显示恢复逻辑处理。
- 失败日志增加完整异常堆栈。退回手机时同步清理眼镜字幕引用。

没有调整头控协议或网络地址。

## 安装与验证

145 项自动测试全部通过，包括新增的 5 项眼镜窗口生命周期测试；`assembleDebug` 和 `lintDebug` 完成，Lint 为 0 错误。自动测试验证了窗口交接、返回页面、临时拒绝重试、取消重试和系统意外关闭窗口。

2026 年 11 月 3 日提供的两组真机日志确认：先连接眼镜再播放、先播放再连接眼镜均正常输出；媒体库窗口交接正常，两组均未发生窗口创建异常或失败重试。详见[真机日志对比结果](display-log-analysis-20261103.md)。这些成功记录验证了当前行为，未还原旧版失败时的具体系统拒绝原因。

修复版安装包：`C:\projects\NeoXR\app\build\outputs\apk\debug\NeoXR.apk`。

手机允许 ADB 调试后，可在 PowerShell 覆盖安装并启动：

```powershell
$neoxrAdb = 'C:\projects\NeoXR\.gradle\codex-toolchain\sdk\platform-tools\adb.exe'
& $neoxrAdb devices -l
& $neoxrAdb install -r 'C:\projects\NeoXR\app\build\outputs\apk\debug\NeoXR.apk'
& $neoxrAdb shell am start -n 'com.neoxr.player/.MainActivity'
```

真机验收顺序：

1. 先连接眼镜，打开应用，在媒体库中点击本地视频。应在眼镜显示视频，手机显示控制器。
2. 返回媒体库，打开另一个视频，确认没有残留的媒体库窗口挡住画面。
3. 先在手机播放，再连接眼镜，确认原来正常的流程仍然正常。
4. 分别测试眼镜二维模式下的单画面，以及匹配半宽／全宽模式的 SBS。

若仍失败，保留眼镜连接，使用无线调试采集：

```powershell
$neoxrAdb = 'C:\projects\NeoXR\.gradle\codex-toolchain\sdk\platform-tools\adb.exe'
& $neoxrAdb logcat -s NeoXR -v threadtime
```

重点保留 `presentation attempt` 和 `presentation FAILED` 后面的完整异常堆栈。

## 签名状态

已使用你补入的 `C:\projects\NeoXR\.android\debug.keystore` 重新构建，并用 `apksigner verify --verbose --print-certs` 验证生成的 APK。签名证书 SHA-256 为 `4471bad7104cdded26da6f5cbba238ea40b6a89112d16698d784fbb7a50cbe53`，与原 APK 完全一致；包名仍为 `com.neoxr.player`，可以使用 `install -r` 覆盖原版，保留现有应用数据。

原 APK 已备份到 `C:\projects\NeoXR\app\build\previous-apk\NeoXR.apk`。

`debug.keystore` 通常位于原构建电脑的 `%USERPROFILE%\.android\debug.keystore`，按旧对话里的用户名可优先检查 `C:\Users\17623\.android\debug.keystore`。如果原环境指定过其他签名位置，可以在原电脑执行 Gradle 的 `signingReport`，查看 `Store` 一行。这里需要旧签名对应的文件，新生成同名文件不能替代它。[Android 应用签名说明](https://developer.android.com/studio/publish/app-signing#debug-mode)

本地再次构建时，将 `ANDROID_USER_HOME` 指向 `C:\projects\NeoXR\.android`，以便继续使用这份调试签名；本次验证脚本已经这样配置。
