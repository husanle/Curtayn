package com.filmritual.camera

import javax.net.ssl.HttpsURLConnection
import java.net.URL

enum class LanguageChoice { AUTO, CHINESE, ENGLISH }
enum class UiLanguage { ZH, EN }

internal fun resolveLanguage(choice: LanguageChoice, country: String?, system: String): UiLanguage = when (choice) {
    LanguageChoice.CHINESE -> UiLanguage.ZH
    LanguageChoice.ENGLISH -> UiLanguage.EN
    LanguageChoice.AUTO -> if (country != null) {
        if (country.equals("CN", true)) UiLanguage.ZH else UiLanguage.EN
    } else if (system.startsWith("zh", true)) UiLanguage.ZH else UiLanguage.EN
}

/** Only a two-letter country response is read; photos and device identifiers are never sent. */
internal fun lookupCountry(): String? = runCatching {
    val connection = URL("https://ipapi.co/country/").openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 3500
        connection.readTimeout = 3500
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Curtayn/0.3.9")
        if (connection.responseCode != 200) return@runCatching null
        val country = connection.inputStream.bufferedReader().use { it.readText().take(16).trim() }
        country.takeIf { it.matches(Regex("[A-Z]{2}")) }
    } finally { connection.disconnect() }
}.getOrNull()

internal fun translate(value: String, language: UiLanguage): String {
    if (language == UiLanguage.ZH) return value
    translations[value]?.let { return it }
    Regex("^(\\d+) 卷$").matchEntire(value)?.let { return it.groupValues[1] + " rolls" }
    // Film metadata and numeric status lines also contain these phrases.
    return phrasePattern.replace(value) { match -> translatedPhrases.getValue(match.value) }
}

private val translations = mapOf(
    "设置" to "Settings", "机身设置" to "Camera settings", "快门" to "Shutter", "过片" to "Advance",
    "取景器" to "Finder", "成片" to "Output", "完成" to "Done", "返回" to "Back", "取消" to "Cancel",
    "平均测光" to "Average metering", "中央权重测光" to "Center-weighted metering", "点测光" to "Spot metering",
    "高光权重测光" to "Highlight-weighted metering", "平均" to "Average", "中央权重" to "Center",
    "点测" to "Spot", "高光权重" to "Highlights", "切换测光方式" to "Change metering mode",
    "让整个取景画面参与测光" to "Meter the entire viewfinder",
    "优先参考中央，兼顾周围" to "Favor the center while including its surroundings",
    "轻点取景窗，测量所点的小区域" to "Tap the viewfinder to meter a small area",
    "优先测量亮部，减少高光溢出" to "Favor bright areas to protect highlights",
    "当前由设备自动测光" to "Device metering: custom regions unavailable",
    "正在应用测光…" to "Applying metering…", "轻点画面选择测光点" to "Tap to place the metering spot",
    "测光调整中…" to "Adjusting exposure…", "兼容测光" to "Compatible metering",
    "请稳住手机" to "Hold steady", "准备取景…" to "Preparing preview…", "相机权限未开启" to "Camera permission required",
    "允许使用相机" to "Allow camera", "镜头" to "Lens", "选胶卷" to "Choose film", "拨杆过片" to "Wind the lever",
    "按住对焦，松手拍" to "Hold to focus, release to shoot", "正在冲印…" to "Developing…",
    "查看相片" to "View photo", "最近一张" to "Latest photo", "相册" to "Gallery",
    "胶片感光度" to "Film sensitivity", "颗粒随档位变粗。手机曝光由相机自动控制。" to "ISO changes grain. Camera auto-exposure controls brightness.",
    "使用胶卷原档 · " to "Use film ISO · ", "胶卷" to "Film", "全部" to "All",
    "实拍与来源" to "Reference", "保留此卷" to "Keep film", "装卷" to "Load film",
    "胶卷或包装实拍" to "Film or packaging reference", "查看厂商资料" to "View manufacturer information",
    "抽屉封面为实拍参考重绘。" to "Film artwork is redrawn from a photographic reference.",
    "使用独立制作的近似色彩曲线。" to "Uses an independently made approximate color curve.", "来源页面" to "Source page",
    "锁定机身品牌" to "Link camera styles", "快门、拨杆和取景器一起切换" to "Change shutter, lever and finder together",
    "Leica M3 实录 · 同系旁轴参考" to "Leica M3 recording · rangefinder reference",
    "该机型真机快门" to "Recorded from this camera", "模拟机械声" to "Simulated mechanical sound",
    "试听快门" to "Preview shutter", "快门与过片共轴" to "Coaxial shutter and lever", "独立快门与拨杆" to "Separate shutter and lever",
    "试听拨杆" to "Preview advance", "ASA 刻度窗" to "ASA scale window", "试听 ISO 卡点" to "Preview ISO click",
    "35mm 胶片齿孔" to "35mm perforated border",
    "随胶卷印上片名、已核对的型号代码、冲洗标记和帧号。即显片保留原画幅。" to "Add film name, verified stock code, process and frame number. Instant film keeps its original frame.",
    "机械声音 · 媒体音量" to "Sounds · media volume", "机械震感" to "Mechanical haptics", "震感强度" to "Haptic strength",
    "手持晃动提示" to "Shake warning", "当前媒体音量为零" to "Media volume is muted",
    "保存失败，可重拍" to "Save failed. Ready to retry.", "请在系统相册的「过片」中查看。" to "Open Pictures/过片 in your gallery.",
    "未知错误" to "Unknown error", "胶卷资料读取失败：" to "Film data failed to load: ", "实时着色失败" to "Live rendering failed",
    "无法打开相机" to "Cannot open camera", "找不到可用后置相机" to "No usable rear camera", "正在打开镜头…" to "Opening lens…",
    "相机未输出画面，请重新打开应用" to "No camera frames. Reopen the app.", "此设备不支持当前相机组合" to "Camera configuration unavailable",
    "时间戳" to "Date imprint", "关闭" to "Off", "尼康参考" to "Nikon inspired", "佳能参考" to "Canon inspired", "富士参考" to "Fujifilm inspired",
    "日期与时间" to "Include time", "橙黄色数码背风格，写入照片右下角。" to "Orange-yellow date-back imprint in the lower right of the photo.",
    "橙黄色日期背，固定在胶片横幅右下；竖拍随画面旋转。" to "Orange-yellow date back, fixed to the film's landscape lower right; rotates with portrait shots.",
    "样式为独立绘制的参考设计，与相机品牌无关联。" to "Independently drawn styles, unaffiliated with camera brands.",
    "语言 / Language" to "Language / 语言", "自动 · IP" to "Auto · IP", "正在查询 IP 地区…" to "Checking IP country…",
    "IP 查询失败，使用系统语言" to "IP lookup unavailable; using system language", "IP 地区：" to "IP country: ",
    "中国大陆使用中文，其他地区使用英文。手动选择始终优先。" to "Chinese in mainland China, English elsewhere. Manual selection takes priority.",
    "自动模式仅向 ipapi.co 查询国家代码；服务可见公网 IP，不上传照片，不使用 GPS。" to "Auto mode asks ipapi.co for a country code. The service sees your public IP. No photos or GPS are sent.",
    "重新检测 IP" to "Refresh IP country", "关于 / Info" to "Info", "使用说明" to "How to shoot",
    "先装一卷，拨一下，再按快门。" to "Load a roll. Wind the lever. Release the shutter.",
    "选择胶卷 → 拨杆过片 → 按住快门对焦，松手拍照。照片保存到 Pictures/过片。" to "Choose film → wind the lever → hold the shutter to focus, release to shoot. Photos save to Pictures/过片.",
    "测光说明" to "Metering guide", "平均：全画面；中央权重：偏重中央；点测光：点击取景窗选点；高光权重：持续寻找亮部并优先测量。" to "Average: full frame. Center-weighted: favor the center. Spot: tap a point. Highlight-weighted: continuously meter bright areas.",
    "测光区域支持由相机驱动决定，不支持时会显示设备自动测光。" to "Metering region support depends on the driver. Unsupported modes show device metering.",
    "点击右下测光符号切换模式。点测光可分别点击亮处和暗处，等待 EV 稳定后拍摄。" to "Tap the lower-right metering symbol to cycle modes. With spot metering, try tapping bright and dark areas. Wait for EV to settle before shooting.",
    "点测光每次点选计算一次曝光补偿，重新构图后再点选。中央权重和高光权重持续检测画面变化。" to "Spot computes exposure compensation once per tap; tap again after reframing. Center-weighted and highlight-weighted modes keep tracking scene changes.",
    "优先使用画面亮度与相机曝光补偿；不支持时尝试硬件区域。两者都不可用时显示设备自动测光。" to "Meters image luminance using camera exposure compensation, with hardware metering regions as a fallback. If neither is available, shows device metering.",
    "隐私" to "Privacy", "照片、滤镜、颗粒与时间戳均在本机处理。无需账号，无广告、无统计，不申请定位权限。" to "Photos, filters, grain and imprints are processed on device. No account, ads, analytics or location permission.",
    "开源与素材" to "Open source and credits", "原创代码采用 MIT 许可。胶卷 LUT、图片和声音依各自许可使用，完整署名随应用附带。" to "Original code: MIT. Film LUTs, images and sounds retain their own licenses. Full credits are bundled with this app.",
    "项目主页" to "Project", "反馈问题" to "Report issue", "查看素材署名" to "View asset credits",
    "镜头诊断" to "Lens diagnostics", "复制诊断" to "Copy diagnostics", "诊断已复制" to "Diagnostics copied",
    "尚未连接相机" to "Camera not connected", "系统未开放的镜头无法强制调用。诊断可用于排查小米等多摄机型。" to "Lenses hidden by the system cannot be forced open. Diagnostics help investigate multi-camera devices.",
    "镜头切换失败，已返回可用镜头" to "Lens unavailable; returned to a working lens",
    "兼容焦段切换" to "Compatible zoom switching", "使用逻辑相机变焦切换焦段，实际物理镜头由系统决定。适合物理镜头切换无效的机型。" to "Switch focal lengths using logical-camera zoom. The system chooses the physical lens. Try this if direct lens switching has no effect.",
    "兼容变焦" to "Compatible zoom", "物理镜头" to "Physical lens", "变焦失败" to "Zoom failed",
    "已请求镜头" to "Requested lens", "返回可用镜头" to "Returned to working lens"
)

private val translatedPhrases = (translations + mapOf(
    "毫米等效" to "mm equivalent", "mm 等效" to "mm equivalent", " 毫米等效" to " mm equivalent",
    "自制近似" to "Custom approximation", "彩色负片" to "Color negative", "彩色反转片" to "Color reversal",
    "黑白负片" to "B&W negative", "黑白即显片" to "B&W instant", "彩色即显片" to "Color instant", "卷壳" to "cartridge", "包装" to "packaging",
    "外盒" to "box", "撕拉即显片盒" to "Peel-apart instant pack", "厂商资料" to "Manufacturer reference",
    "亮框旁轴 · 左侧测光" to "Bright-frame rangefinder · left meter", "裂像单反 · 侧边标尺" to "Split-image SLR · side scale",
    "亮框旁轴" to "Bright-frame rangefinder", "旁轴主取景窗" to "Rangefinder window", "裂像单反" to "Split-image SLR",
    "三组亮框旁轴" to "Three-frame rangefinder", "明亮取景窗" to "Bright viewfinder", "指针测光单反" to "Needle-meter SLR",
    "徕卡" to "Leica", "蔡司" to "Zeiss", "尼康" to "Nikon", "佳能" to "Canon", "宾得" to "Pentax", "爱克发" to "Agfa",
    "黑漆滚花" to "Black knurl", "银色金属" to "Silver metal", "黑色树脂" to "Black resin",
    "黑漆与皮纹" to "Black lacquer & leather", "镜头拍摄尺寸" to "Capture size", "最近成片" to "Last photo",
    "深色缎面金属" to "Dark satin metal", "浅银缎面与皮纹" to "Silver satin & leather", "冷银拉丝与黑皮纹" to "Brushed silver & black leather",
    "暖银缎面与黑皮纹" to "Warm silver & black leather", "石墨漆与细纹蒙皮" to "Graphite & fine leather",
    "银色金属与粗纹蒙皮" to "Silver & textured leather", "哑光树脂" to "Matte resin", "修图" to "retouch",
    "颗粒感光度 ISO " to "Grain ISO ", "选择 ISO 档位" to "Choose ISO", "过片拨杆 " to "Advance lever ", "拍照" to "Take photo"
))
private val phrasePattern = Regex(translatedPhrases.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) })
