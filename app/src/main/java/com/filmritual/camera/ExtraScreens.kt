package com.filmritual.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun ImprintPreview(app: MainActivity, modifier: Modifier, displayScale: Float = 1f, followCamera: Boolean = true) {
    val now by produceState(Instant.now()) {
        while (true) { value = Instant.now(); delay(1000) }
    }
    val stamp = DateImprint(app.imprintStyle, app.imprintTime, now, ZoneId.systemDefault())
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawFilmDateImprint(stamp, android.graphics.RectF(0f, 0f, size.width, size.height),
                if (followCamera) app.camera.photoRotation else 0, displayScale)
        }
    }
}

@Composable
internal fun ImprintSettings(app: MainActivity) {
    FilmText("时间戳", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    ImprintStyle.entries.forEach { style ->
        Choice(style.title, when (style) {
            ImprintStyle.OFF -> ""
            ImprintStyle.NIKON -> "'26 11 22"
            ImprintStyle.CANON -> "26  11  22"
            ImprintStyle.FUJI -> "2026.11.22"
        }, style == app.imprintStyle) { app.chooseImprint(style) }
    }
    if (app.imprintStyle != ImprintStyle.OFF) {
        Toggle("日期与时间", app.imprintTime, app::toggleImprintTime)
        ImprintPreview(app, Modifier.fillMaxWidth().height(78.dp).clip(shape).background(Color(0xff353128)), displayScale = 7f, followCamera = false)
    }
    FilmText("橙黄色日期背，固定在胶片横幅右下；竖拍随画面旋转。", color = muted, fontSize = 12.sp)
    FilmText("样式为独立绘制的参考设计，与相机品牌无关联。", color = muted, fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
internal fun LanguageSettings(app: MainActivity) {
    for ((choice, title) in listOf(LanguageChoice.AUTO to "自动 · IP", LanguageChoice.CHINESE to "简体中文", LanguageChoice.ENGLISH to "English")) {
        Choice(title, "", app.languageChoice == choice) { app.chooseLanguage(choice) }
    }
    FilmText("中国大陆使用中文，其他地区使用英文。手动选择始终优先。", fontSize = 12.sp, color = muted)
    if (app.languageChoice == LanguageChoice.AUTO) {
        FilmText(when {
            app.languagePending -> "正在查询 IP 地区…"
            app.languageCountry != null -> app.text("IP 地区：") + app.languageCountry
            else -> "IP 查询失败，使用系统语言"
        }, fontSize = 12.sp, color = brass, modifier = Modifier.padding(vertical = 10.dp))
        TextButton(onClick = { app.detectLanguage(force = true) }, enabled = !app.languagePending) { FilmText("重新检测 IP") }
    }
    FilmText("自动模式仅向 ipapi.co 查询国家代码；服务可见公网 IP，不上传照片，不使用 GPS。", fontSize = 12.sp, color = muted)
}

@Composable
internal fun InfoDialog(app: MainActivity, dismiss: () -> Unit) {
    var credits by remember { mutableStateOf(false) }
    val version = remember { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.3.9" }
    Dialog(dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).clip(shape).background(deck).padding(18.dp)) {
            FilmText("Curtayn", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = paper)
            FilmText("v$version · Android ${android.os.Build.VERSION.RELEASE}", color = brass, fontSize = 12.sp)
            FilmText("先装一卷，拨一下，再按快门。", color = muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            HorizontalDivider(color = line)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                InfoSection("使用说明", "选择胶卷 → 拨杆过片 → 按住快门对焦，松手拍照。照片保存到 Pictures/过片。")
                InfoSection("测光说明", "平均：全画面；中央权重：偏重中央；点测光：点击取景窗选点；高光权重：持续寻找亮部并优先测量。")
                FilmText("点击右下测光符号切换模式。点测光可分别点击亮处和暗处，等待 EV 稳定后拍摄。", color = muted, fontSize = 12.sp)
                FilmText("点测光每次点选计算一次曝光补偿，重新构图后再点选。中央权重和高光权重持续检测画面变化。", color = muted, fontSize = 12.sp)
                FilmText("优先使用画面亮度与相机曝光补偿；不支持时尝试硬件区域。两者都不可用时显示设备自动测光。", color = muted, fontSize = 12.sp)
                InfoSection("隐私", "照片、滤镜、颗粒与时间戳均在本机处理。无需账号，无广告、无统计，不申请定位权限。")
                FilmText("自动模式仅向 ipapi.co 查询国家代码；服务可见公网 IP，不上传照片，不使用 GPS。", color = muted, fontSize = 12.sp)
                InfoSection("开源与素材", "原创代码采用 MIT 许可。胶卷 LUT、图片和声音依各自许可使用，完整署名随应用附带。")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({ app.openUrl("https://github.com/husanle/Curtayn") }) { FilmText("项目主页") }
                    TextButton({ app.openUrl("https://github.com/husanle/Curtayn/issues") }) { FilmText("反馈问题") }
                }
                TextButton({ credits = true }) { FilmText("查看素材署名") }
                InfoSection("镜头诊断", "系统未开放的镜头无法强制调用。诊断可用于排查小米等多摄机型。")
                Toggle("兼容焦段切换", app.compatibleZoom, app::toggleCompatibleZoom)
                FilmText("使用逻辑相机变焦切换焦段，实际物理镜头由系统决定。适合物理镜头切换无效的机型。", color = muted, fontSize = 12.sp)
                FilmText("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", color = paper, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp))
                FilmText(app.camera.diagnostics.ifBlank { "尚未连接相机" }, fontFamily = mono, color = muted, fontSize = 10.sp)
                TextButton(app::copyDiagnostics) { FilmText("复制诊断") }
            }
            TextButton(dismiss, Modifier.align(Alignment.End)) { FilmText("完成") }
        }
    }
    if (credits) CreditsDialog(app) { credits = false }
}

@Composable
private fun InfoSection(title: String, body: String) {
    FilmText(title, color = brass, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(top = 15.dp, bottom = 6.dp))
    FilmText(body, color = paper, fontSize = 12.sp, lineHeight = 19.sp)
}

@Composable
private fun CreditsDialog(app: MainActivity, dismiss: () -> Unit) {
    val content by produceState("") {
        value = withContext(Dispatchers.IO) {
            (listOf("info/LICENSE", "info/NOTICE.md", "info/ASSETS.md") +
                app.assets.list("licenses").orEmpty().map { "licenses/$it" }).joinToString("\n\n") { file ->
                file + "\n\n" + app.assets.open(file).bufferedReader().use { it.readText() }
            }
        }
    }
    Dialog(dismiss) {
        Column(Modifier.heightIn(max = 650.dp).clip(shape).background(deck).padding(16.dp)) {
            FilmText("开源与素材", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text(content, Modifier.weight(1f).verticalScroll(rememberScrollState()), color = muted, fontSize = 11.sp)
            TextButton(dismiss, Modifier.align(Alignment.End)) { FilmText("返回") }
        }
    }
}
