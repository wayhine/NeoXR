# 雷鸟 GT 陀螺仪与 NeoXR 头控接入核查

核查日期为2026-11-17，手机陀螺仪及网络媒体库修复复测日期为2026-11-18。

## 结论

雷鸟GT已经在Find X9 Pro上成功输出IMU回调和姿态四元数。独立测试程序采样约302秒，取得140752次IMU回调，平均约467次/秒。手机固定时，眼镜姿态最大变化约131.13°，手机姿态最大变化约0.94°。

NeoXR已经加入雷鸟USB识别、SDK姿态读取、超时和断连处理，并沿用播放器的头控、灵敏度与回正入口。当前167项单元测试通过，APK构建与lint检查通过。播放器“头控”入口的连接、关闭及再次开启已经实测，均能重新取得眼镜姿态。用户已确认初版播放和眼镜头控方向正常。

雷鸟官方说明GT采用Zone 360 3DoF芯片，支持头部旋转追踪与空间显示模式。3DoF对应旋转姿态，位置平移跟踪需要另外的硬件能力。[官方GT系列支持说明](https://www.rayneo.com/pages/support-rayneo-gt-series-ar-glasses)

## REA确认的调用路径

分析来自本机RayNeoXR 2.1.2 APK，SHA-256为`cffcf418f579df0cec335f6e2d66fdb790cb831ca1c5323906ac0e949e9d1ffa`。本次继续使用REA 6.9.0及JADX 0.7.1读取静态实现。

```text
AirApi.init(context)
  -> RegisterStateEventListener(...)
  -> FFalconXRServer.InitAndroid(context)
      -> InitJNI(context)
      -> MonitorService
          -> UsbManager.requestPermission(...)
          -> UsbManager.openDevice(...)
          -> EstablishUsbConnection(fileDescriptor)
  -> StartXR()

AirApi.openSensor() -> OpenIMU()
AirApi.getPosQuaternion() -> GetHeadTrackerPose().GetQuaternion()
IMUEventCallback(...) -> IMUEventListener.onSensorChanged(...)
```

其中`EstablishUsbConnection()`返回0时，Java服务记录原生连接成功。SDK服务处理USB设备生命周期，NeoXR可以围绕这条USB与IMU通道建立独立头控后端。

### 已取得的接口

| 接口 | 静态观察 |
| --- | --- |
| `OpenIMU()` / `CloseIMU()` | 开启和关闭IMU的native声明 |
| `GetHeadTrackerPose()` | 返回`FXRPoseInfo` |
| `FXRPoseInfo.GetQuaternion()` | 返回`rotation`数组的4个分量 |
| `GetHeadTrackerStatus()` | native状态查询接口 |
| `RegisterIMUEventListener()` | 注册传感器监听器 |
| `IMUEventCallback(float[], float[], float[], long)` | 将三组数组与一个long值转发给监听器 |
| `RecenterHeadTracker()` | SDK提供的回正接口 |
| `AirApi.GetGyro()` | 当前反编译方法体直接返回`null` |

实机静止四元数接近`[0, 0, 0, 1]`，分量排列为`x, y, z, w`，连续采样的范数接近1。`GetQuaternionLeftHand()`对x、y分量取反，当前接入采用原始右手四元数。三组原始IMU数组的完整单位和时间戳语义仍待核实，当前播放器使用SDK融合后的姿态四元数。

这副GT在成功输出数据时，`InitJNI()`返回`false`，`GetHeadTrackerStatus()`返回`-1`。官方Java封装没有检查`InitJNI()`的返回值，实机USB握手返回0，IMU回调持续增长。因此接入通过USB握手、有效四元数和IMU回调的时效性判断工作状态。

### USB设备筛选

本次APK的`MonitorService.isUsbDeviceVerified()`接受以下VID/PID组合。

| VID | PID |
| --- | --- |
| `0x1BBB`（7099） | `0xAF50`（44880） |
| `0x3941`（14657） | `0xAF50`（44880） |

实机GT枚举为`3941:AF50`，设备名称为`RayNeo AR Glasses`。接口0为HID，含64字节中断IN端点`0x81`和OUT端点`0x02`，轮询间隔为4。手机型号为`PLG110`，SoC为`MT6993`，Android API为37。

原厂设备过滤XML还登记了`3941:AF51`，NeoXR同步识别该编号。本次连续采样使用`3941:AF50`。

## NeoXR现状与接入方式

当前[Imu.kt](../app/src/main/java/com/neoxr/player/Imu.kt)的`HeadTracker`会优先识别雷鸟设备，再进入XREAL Air USB或One系列网络路径。雷鸟后端位于[RayNeoImu.kt](../app/src/main/java/com/neoxr/player/RayNeoImu.kt)，姿态转换位于[RayNeoPose.kt](../app/src/main/java/com/neoxr/player/RayNeoPose.kt)。姿态通过已有`onOrientation(yaw, pitch)`送入播放器，开启时以首个有效姿态居中。

实机验证记录如下。

1. 已取得USB设备ID、接口和授权，原生库加载成功。
2. `EstablishUsbConnection(fileDescriptor)`返回0，`OpenIMU()`后持续触发`onSensorChanged()`。
3. 固定手机并单独转动眼镜，确认姿态来源。
4. 四元数转换的顺序、偏航、俯仰、纯滚转和无效输入已有回归测试。
5. 已实现独立USB生命周期，连接、采样与清理在同一串行执行器运行。停止时关闭IMU、通知断连、停止SDK并释放USB连接。

## 本地运行库准备

当前实机版本从用户提供的RayNeoXR 2.1.2 APK提取36个`com.ffalcon.xr`类和4个原生库，在独立类加载器中使用。NeoXR负责USB授权、连接和清理，直接调用`InitJNI()`，避免启动原厂UI与服务。生成文件放在`app/src/main/assets/rayneo`，已经加入Git忽略规则。

准备命令在项目根目录的PowerShell运行。将示例中的APK和工具JAR路径替换为实际位置，`JAVA_HOME`指向JDK 17。

```powershell
$rayneoApk = 'C:\path\RayNeoXR_V2.1.2_cn_release_2026.09.29_19.33.39.apk'
$jadxJar = 'C:\path\jadx-headless-mcp-0.7.1-all.jar'
python scripts\prepare-rayneo-runtime.py $rayneoApk --dexlib-jar $jadxJar --jdk $env:JAVA_HOME
```

生成本地运行库后，按项目正常Gradle流程构建APK。类加载器使用应用私有代码缓存，并在写入动态代码前将文件设为只读，以兼容Android 14及以上的要求。当前提取脚本校验本次已分析APK的SHA-256，其他版本需要重新核对SDK接口。

GT自带空间显示也会处理头部姿态，测试NeoXR渲染时需要确认眼镜当前显示模式，观察两套姿态处理叠加后的画面效果。

## 证据与待验证项

本地完整记录目录为`reverse-analysis/RayNeoXR-2.1.2/rayneo-imu`。

| 观察 | REA Evidence ID |
| --- | --- |
| USB设备筛选 | `ev_b7eef91640978f8b8efaa90d917ee57ca606204271f9ce5b58b299f9870914c8` |
| USB打开与FD传递 | `ev_a5800c6a0f7be60cf0e954168bb7399afd2914f20a0d6975330daca77ff1475a` |
| USB授权请求 | `ev_9589ab2368ba067f058eeb278fd2be611784c484274f7ba11fea3645a72ce7f1` |
| SDK初始化服务 | `ev_ce13083319cf15777894428c3c4f0e445e9ba5d7f10307f067e9cd0d24b7d9c2` |
| 姿态四元数读取 | `ev_c118a26019829dabc8c0719be8b4048c9a6121bb68280d907777d2b046c22b5d` |
| IMU事件转发 | `ev_506676a2622c57e97c1cec55a723ea7cb69340033eea819ed3f47c8735ee2818` |
| GetGyro方法体 | `ev_9061346164a26e35de08d502c1532037ea8c59a1676c745eab4819318aecfa85` |

实机CSV、日志和汇总位于`reverse-analysis/RayNeoXR-2.1.2/rayneo-imu/hardware-20261117-225557`。独立探针包名为`com.neoxr.rayneo.probe`。

NeoXR实机日志位于`rayneo-imu/neoxr-head-tracking.log`和`rayneo-imu/neoxr-entry.log`。日志记录外屏14上的Presentation成功显示、GL Surface就绪以及雷鸟姿态成功读取。项目演示视频第一次通过ADB标准输入传输时不完整，后续通过`adb push`及应用身份复制到私有目录，手机与电脑SHA-256一致，外屏截图已经取得视频画面。

## 手机陀螺仪和页面交接修复

用户随后反馈，关闭眼镜头控后手机左右转动过于灵敏，首页进入网络媒体库时会短暂出现手机控制界面。检查发现，两个传感器共用灵敏度，设备保存值为150，切回手机时保留了眼镜的回正基准。手机接近平放时，背面朝向的水平投影接近零，原有方位角会变得不稳定。

手机现在单独保存`phoneSens`，默认50，可在“画质 → 手机陀螺仪灵敏度”调整。平放时切换到手机顶边作为水平参考，并使用滞回及连续的角度偏移处理参考轴切换。关闭眼镜头控、传感器断开和播放器停止后，再切回手机时以首个手机姿态建立新基准，保留切换前的画面位置。眼镜回调在主线程处理，并过滤已停止实例的排队样本。

外屏窗口原来在`onPause`关闭，新窗口在下个页面`onResume`后显示，中间会暴露手机镜像。初次将释放时间延至`onStop`后，实机仍捕获到OPPO连续两次拒绝新窗口的`InvalidDisplayException`，然后才显示目标页面。

最终将原网络媒体库逻辑提取为`NetworkLibraryScreen`，首页在现有Activity及Presentation中替换内容，返回时显示原首页。独立的`NetworkLibraryActivity`入口沿用同一组件。内容切换保留触控板和光标，并保存目录、搜索和网络页状态，返回通过AndroidX返回分发器处理。回归测试覆盖同一外屏窗口、搜索框隔离、页面重建及返回恢复。

实机录屏连续完成两次“首页进入网络媒体库、返回首页”。日志记录2次打开及2次关闭，期间窗口显示、隐藏及拒绝均为0次。录屏已核对，页面直接切换，过程中没有露出手机控制界面。证据位于`rayneo-imu/library-handoff-inplace.log`、`rayneo-imu/library-handoff-inplace.mp4`及`rayneo-imu/screens/handoff-inplace-roundtrip.png`。

发布前构建已使用原有调试签名通过无线ADB覆盖安装。手机“画质”面板已确认分别显示眼镜头控150和手机陀螺仪50。GT重新连接后识别为`3941:AF50`，1920×1080外屏为逻辑显示15。用户随后确认新版手机陀螺仪手感正常。本轮手机灵敏度及网络媒体库切换修复已完成验证，眼镜头控物理拔插后的恢复仍待实测。1.7.0发布包和校验值随GitHub Release提供。
