# Curtayn

**下载地址**
通过网盘分享的文件：Curtayn-0.3.4
链接: https://pan.baidu.com/s/1DjXrYG8Z4JV7UBv5PxjS4A?pwd=3i34 提取码: 3i34 
--来自百度网盘超级会员v9的分享

**先装一卷，拨一下，再按快门。**

Curtayn 是一款离线 Android 胶片模拟相机。做这个项目，是想把按下快门前的那几秒留在手机上。看到卷壳、选定一卷，再听机械拨杆到位的那声咔哒，每个动作都能让人重新留意正在拍什么。

**[下载 Android 试用版](https://github.com/husanle/Curtayn/releases/tag/v0.3.4)** · [素材与署名](ASSETS.md) · [开放源代码许可](NOTICE.md) · [隐私说明](PRIVACY.md)

## 有什么

- **48 款胶卷模拟。** 从富士、柯达、Fomapan、Ilford、Kentmere 等胶卷的壳或包装选卷，实时预览。24 款使用 Film-Luts，另外 24 款采用 Curtayn 自制的近似曲线。
- **机身、快门与过片联动。** 在 Leica M6、Nikon FM2／SP、Zeiss Ikon ZM、Canon P／AE-1、Pentax K1000 和 Agfa Optima Sensor 等参考风格间切换。右边拨杆有短拨累积、到位和回弹。快门、棘轮和 ISO 拨盘都有可试听的机械音效及振动。
- **颗粒可以转出来。** 拨 ASA/ISO 档位改变颗粒，细粒主要落在中间调。设备实拍的曝光参数继续写入 JPEG 的 EXIF。
- **片边也跟着胶卷走。** 成片可加 35mm 齿孔、胶卷名、已经核对的型号代码和帧号。画面完整保留在片边以内。
- **按手机公开能力拍摄。** 识别 35mm 等效焦段，优先请求该镜头可用的高分辨率 JPEG。桌面构建和单元测试可复现，副摄与高像素仍需按机型验证。

## 怎么拍

1. 授予相机权限，点底部当前胶卷的缩略图，按品牌挑选。点某卷预览颜色，点「装卷」切换。「实拍与来源」展示包装依据和署名。卡片会标出 Film-Luts 或自制近似。
2. 在「设置」选 **快门、过片、ISO 拨盘和取景器**。快门风格决定机身纹理。打开默认的「锁定机身品牌」时，快门、拨杆与取景器一起切换。关闭后可混搭。Leica M6 TTL 和 Agfa Optima 1035 拨杆把快门安在过片转轴上。设置页可试听三种动作，声音使用媒体音量，震感强度可独立调节。
3. 沿右侧过片杆的弧线拨到底并松手。也可连续短拨，靠棘轮累计行程。每次拨动都有回程，到位后才上弦。按住快门对焦，松手拍照。保存成功后计帧并等待再次过片。
4. 旋转左侧 ASA 拨盘，或轻点拨盘打开档位表。装新卷时使用该卷参考感光度。较高档位有稍粗、稍明显的颗粒，阴影和高光处更淡。实时预览和成片共用颗粒算法，JPEG 的真实传感器 ISO 继续由原始 EXIF 记录。
5. 在胶卷旁选择 35 mm 等效焦段。Curtayn 从设备公开的 Camera2 镜头参数计算并显示可选焦段。优先绑定高分辨率 JPEG，若当前镜头组合不支持则重试常规拍摄。系统相册的 `Pictures/过片` 可查看照片。
6. 在「设置 → 成片」开启「35mm 胶片齿孔」。横拍加在上下边，竖拍加在左右边。边缘按所选胶卷印片名、已核对的型号代码、冲洗标记和当前帧号。保留拍摄画面，增加 JPEG 外边缘。

## 构建与安装

公开试用包为 Android APK。需 Android 11 或更新版本、后置相机和 OpenGL ES 3.0。到 Release 的 **Assets** 获取 `Curtayn-0.3.4.apk`。 [v0.3.4 下载页](https://github.com/husanle/Curtayn/releases/tag/v0.3.4)

从源码构建需要 JDK 17、Android SDK 36 和 Build Tools 35.0.0，并设置 `JAVA_HOME` 与 `ANDROID_HOME`。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

macOS 和 Linux 使用 `./gradlew :app:assembleDebug :app:testDebugUnitTest`。构建产物在 `app/build/outputs/apk/debug/`。

## 真机验收

- 在亮处逐个切换检测到的焦段，遮住对应镜头确认换摄，检查 JPEG EXIF 焦段。核对「设置 → 成片」里的镜头尺寸与保存的实际像素。开启齿孔时，实际像素包含新增片边。
- 同一场景分别用彩色、黑白和即显片拍摄，核对预览与成片颜色、图片方向和标线。将 ISO 拨到 100、400、1600，比较颗粒。
- 检查拨杆半程松手、到底回弹、未过片禁拍和快门移出取消。切换快门、过片和 ISO 拨盘后重启，确认设置恢复。自定义声音关闭时仍保留系统要求的快门声。

## Android 设备与分辨率

应用要求 Android 11+、后置相机和 OpenGL ES 3.0。相机驱动决定了第三方应用能用的镜头与 JPEG 输出。厂商相机的专用高像素模式可能另有访问限制。Curtayn 对相机返回的完整 JPEG 分块处理，按拍摄方向旋转，保留原始像素；胶片齿孔加在画面外。

不同品牌的 Android 机型使用同一 APK。具体可用焦段、振动与输出尺寸取决于机型和系统版本。HarmonyOS 5 及以上的 APK 安装兼容范围以卓易通的实际支持为准。 [CameraX 配置](https://developer.android.com/media/camera/camerax/configuration) · [华为安装说明](https://consumer.huawei.com/cn/support/content/zh-cn16061787/)

反馈时附上机型、系统版本、所选焦段与成片尺寸即可，便于复现。 [Issues](https://github.com/husanle/Curtayn/issues)

## 许可

Curtayn 的原创代码和项目文档采用 MIT 许可。第三方素材遵循各自的许可和署名。Curtayn 是独立项目。 [MIT](LICENSE) · [权利声明](NOTICE.md) · [素材说明](ASSETS.md) · [隐私](PRIVACY.md)
