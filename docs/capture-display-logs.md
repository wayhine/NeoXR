# 采集 NeoXR 眼镜输出异常日志

本机已经有 ADB 和采集脚本，无需 Android Studio。眼镜占用手机 USB-C 接口，建议使用无线调试，手机和电脑接入同一个 Wi-Fi。

## 1. 连接无线 ADB

手机打开“开发者选项 → 无线调试 → 使用配对码配对设备”。在电脑的 PowerShell 执行：

```powershell
$neoxrAdb = 'C:\projects\NeoXR\.gradle\codex-toolchain\sdk\platform-tools\adb.exe'
& $neoxrAdb pair '192.168.0.131:32789'
```

把引号里的内容替换为配对窗口显示的 IP 和端口，再按提示输入配对码。

回到手机“无线调试”主页面，使用该页面的 IP 和连接端口：

```powershell
& $neoxrAdb connect '192.168.0.131:32789'
& $neoxrAdb devices -l
```

配对端口和连接端口通常不同。连接成功后，设备状态应为 `device`。记录 `devices -l` 输出第一列的完整值，后面的脚本参数 `DeviceSerial` 使用这个值；即使设备已经自动连接，也以这个值为准。

这是 Android 官方支持的无线配对与调试流程。[ADB 无线调试说明](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)

## 2. 采集“先连接眼镜，再播放”的日志

执行下面的命令，将 `设备第一列的完整值` 换成刚才看到的值：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'C:\projects\NeoXR\tools\Capture-NeoXRDisplayLogs.ps1' -DeviceSerial '设备第一列的完整值' -Scenario Preconnected
```

脚本运行后，按屏幕提示操作：

1. 插好眼镜，确认无线 ADB 仍连接，按 Enter。脚本会重启 NeoXR，不清除应用数据。
2. 首页／媒体库出现后，先不要打开视频，按 Enter，让脚本记录媒体库窗口状态。
3. 再点击视频，等出现手机播放、眼镜无画面或错误提示后，继续等待约 5 秒。
4. 在 PowerShell 简短输入实际结果，例如“视频退回手机，眼镜停在媒体库”，然后按 Enter。

脚本会结束采集，并输出 ZIP 文件的完整路径。

## 3. 再采集“先播放，再连接眼镜”的对照日志

保持无线 ADB，执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File 'C:\projects\NeoXR\tools\Capture-NeoXRDisplayLogs.ps1' -DeviceSerial '设备第一列的完整值' -Scenario ConnectDuringPlayback
```

先拔下眼镜，按提示重启应用、记录媒体库状态；然后在手机播放视频，再插入眼镜，等约 5 秒后输入实际结果并结束。尽量使用与第一组相同的视频及眼镜显示模式。

## 4. 提供哪些文件

把两次生成的 ZIP 提供给我。它们位于：

```text
C:\projects\NeoXR\app\build\diagnostics\
```

每组包含：

- `logcat.txt`：从复现前开始记录的应用日志、系统日志和崩溃日志，包含完整异常堆栈。
- `before-*`、`menu-*`、`after-*`：复现前、媒体库页面、播放结果出现后的显示器、窗口和 Activity 状态。
- `installed-package.txt`：所采集版本的应用信息。
- `capture-info.txt`：测试顺序、时间和你描述的结果。

重点会检查：

| 日志内容 | 用于判断 |
|---|---|
| `glassesDisplay=`、`display id=` | 是否识别到正确的外接屏 |
| `presentation attempt`、`presentation FAILED` 及后续堆栈 | 显示窗口创建在哪一步失败 |
| `InvalidDisplayException`、`BadTokenException`、系统窗口日志 | 系统拒绝窗口的具体原因 |
| `presentation shown` | 是否已经成功显示眼镜窗口 |
| 三阶段的窗口、Activity 和显示器状态 | 是否存在旧窗口残留、任务所在屏幕变化或外接屏被移除 |

日志采集不使用 `--pid`，因为页面重建或进程重新启动后 PID 可能变化；也不只筛选 NeoXR 标签，以便保留系统拒绝窗口时的上下文。[Logcat 日志缓冲区与过滤说明](https://developer.android.com/tools/logcat)

请使用实际出现问题的版本采集。若修复版已经无法复现，成功日志可以验证修复效果，但无法单独证明旧版当时触发了哪条系统限制。修复版包含更完整的异常堆栈记录，若它仍然失败，请优先提供修复版日志。
