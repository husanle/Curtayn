package com.filmritual.camera

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val ink = Color(0xff141412)
internal val deck = Color(0xff1c1c19)
internal val line = Color(0xff403c32)
internal val paper = Color(0xfff0e8d6)
internal val muted = Color(0xffaaa390)
internal val brass = Color(0xffd9b475)
private val signal = Color(0xffda654c)
internal val shape = RoundedCornerShape(10.dp)
internal val mono = FontFamily.Monospace

@Composable
fun CameraScreen(app: MainActivity) {
    var settings by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<Film?>(null) }
    BackHandler(app.picker != null || settings || info || original != null) {
        when { info -> info = false; original != null -> original = null; settings -> settings = false; else -> app.cancelPicker() }
    }
    val scheme = darkColorScheme(primary = brass, onPrimary = ink, background = ink, onBackground = paper,
        surface = deck, onSurface = paper, outline = line, secondary = muted)
    val textures = rememberBodyTextures()
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(ink)) {
            CameraBody(app.shutter.finish, textures, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, top = 5.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.curtayn_emblem), null,
                        Modifier.size(34.dp).clip(shape).background(colorResource(R.color.curtayn_steel_background)))
                    Spacer(Modifier.width(9.dp))
                    FilmText(stringResource(R.string.app_name), Modifier.weight(1f), color = Color(0xffe0eaf0),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .65.sp)
                    Control("Info", { info = true }, selected = info)
                    Spacer(Modifier.width(5.dp))
                    Control("设置", { settings = true }, selected = settings)
                }
                Viewfinder(app, textures, Modifier.weight(1f))
                if (app.picker != null) FilmDeck(app) { original = it } else {
                    ShootingDeck(app, textures)
                }
            }
        }
        if (settings) SettingsDialog(app) { settings = false }
        if (info) InfoDialog(app) { info = false }
        original?.let { ReferenceDialog(app, it) { original = null } }
    }
}

@Composable
private fun Viewfinder(app: MainActivity, textures: BodyTextures?, modifier: Modifier) {
    var focus by remember { mutableStateOf<Offset?>(null) }
    var focusCount by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(focusCount) { if (focusCount > 0) { delay(780); focus = null } }
    LaunchedEffect(app.flash) { if (app.flash > 0) { flash = true; delay(85); flash = false } }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val lip = 2.dp
        val opening = minOf(maxWidth - lip * 2, (maxHeight - lip * 2) * .75f)
        val glass = RoundedCornerShape(5.dp)
        if (opening > 0.dp) {
            Box(Modifier.size(opening + lip * 2, opening / .75f + lip * 2)
                .viewfinderBezel(app.shutter.finish, textures).padding(lip)) {
                Box(Modifier.fillMaxSize().clip(glass).background(Color(0xff070809))) {
                    AndroidView(factory = { app.preview }, modifier = Modifier.matchParentSize())
                    if (app.imprintStyle != ImprintStyle.OFF) ImprintPreview(app, Modifier.matchParentSize())
                    val spot = if (app.camera.ready && app.camera.metering != MeteringStatus.SYSTEM && app.meteringMode == MeteringMode.SPOT) {
                        MeteringMode.SPOT.zones(app.meteringSpot, 1).first()
                    } else null
                    FinderOverlay(app.finder, app.camera.exposure, focus, spot, Modifier.matchParentSize().pointerInput(app) {
                        detectTapGestures { point ->
                            if (app.camera.ready) { focus = point; focusCount++; app.focus(point.x, point.y) }
                        }
                    })
                    Box(Modifier.matchParentSize().border(1.dp, Color.Black.copy(alpha = .74f), glass))
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Badge(app.finder.style)
                        Badge((app.camera.current?.let { it.toString() + " mm EQ" } ?: "AUTO") + if (app.compatibleZoom) " · Z" else "")
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { MeteringControl(app) }
                    if (app.shaky && app.camera.ready) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { Badge("请稳住手机", signal) }
                    }
                    if (flash) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .78f)))
                    if (!app.permitted || !app.camera.ready) Column(Modifier.align(Alignment.Center)
                        .background(ink.copy(alpha = .9f), shape).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilmText(if (app.permitted) app.camera.message ?: "准备取景…" else "相机权限未开启", color = paper, fontSize = 13.sp)
                        if (!app.permitted) TextButton(onClick = app::permission) { FilmText("允许使用相机") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinderOverlay(finder: Finder, exposure: Exposure, focus: Offset?, spot: MeteringZone?, modifier: Modifier) {
    Canvas(modifier) {
        val border = when (finder) {
            Finder.LEICA, Finder.CANON -> Color(0xffefe1ba)
            Finder.ZEISS, Finder.NIKON_SP -> Color(0xffe2eefb)
            Finder.NIKON, Finder.AE1 -> Color.White
            Finder.AGFA -> Color(0xffedba57)
            Finder.PENTAX -> Color(0xffe4dbcb)
        }.copy(alpha = .8f)
        val length = size.width * .065f
        fun frame(insetX: Float, insetY: Float, color: Color, tick: Float) {
            val left = size.width * insetX
            val top = size.height * insetY
            for (x in listOf(left, size.width - left)) for (y in listOf(top, size.height - top)) {
                val corner = Offset(x, y)
                drawLine(color, corner, Offset(x + if (x == left) tick else -tick, y), 1.3.dp.toPx())
                drawLine(color, corner, Offset(x, y + if (y == top) tick else -tick), 1.3.dp.toPx())
            }
        }
        frame(.08f, .10f, border, length)
        val center = this.center
        val radius = size.width * .068f
        when (finder) {
            Finder.LEICA, Finder.ZEISS, Finder.NIKON_SP, Finder.CANON -> {
                val patch = if (finder == Finder.NIKON_SP) radius * .7f else radius
                drawRect(border, Offset(center.x - patch, center.y - patch * .56f), Size(patch * 2, patch * 1.12f),
                    style = Stroke(1.2.dp.toPx()))
                if (finder == Finder.CANON) {
                    frame(.206f, .22f, border.copy(alpha = .48f), length * .78f)
                    frame(.353f, .36f, border.copy(alpha = .38f), length * .66f)
                }
            }
            Finder.AGFA -> drawRect(border, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(1.dp.toPx()))
            Finder.NIKON, Finder.AE1, Finder.PENTAX -> {
                drawCircle(border, radius, center, style = Stroke(1.dp.toPx()))
                if (finder == Finder.NIKON || finder == Finder.AE1) {
                    drawCircle(border.copy(alpha = .45f), radius * 1.55f, center, style = Stroke(1.dp.toPx()))
                }
                drawLine(border, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
            }
        }
        val meter = when (exposure) { Exposure.READY -> Color(0xff91c67a); Exposure.LOW -> signal; Exposure.METERING -> brass; Exposure.UNKNOWN -> null }
        if (meter != null && finder != Finder.CANON && finder != Finder.NIKON_SP) {
            val meterX = size.width * .94f
            val meterY = size.height * .51f
            if (finder == Finder.PENTAX || finder == Finder.AE1) {
                val half = 18.dp.toPx()
                drawLine(border, Offset(meterX, meterY - half), Offset(meterX, meterY + half), 1.dp.toPx())
                if (finder == Finder.AE1) {
                    for (division in -2..2) {
                        val y = meterY + division * half / 2f
                        drawLine(border, Offset(meterX - 3.dp.toPx(), y), Offset(meterX, y), 1.dp.toPx())
                    }
                }
                val travel = if (exposure == Exposure.LOW) 17.dp.toPx() else if (exposure == Exposure.METERING) 7.dp.toPx() else 0f
                drawLine(meter, Offset(meterX - 9.dp.toPx(), meterY + travel), Offset(meterX + 3.dp.toPx(), meterY + travel), 2.dp.toPx())
            } else {
                val where = when (finder) {
                    Finder.NIKON -> Offset(meterX, meterY)
                    Finder.ZEISS -> Offset(size.width * .065f, meterY)
                    else -> Offset(size.width * .19f, size.height * .89f)
                }
                drawCircle(meter, 3.dp.toPx(), where)
                if (finder == Finder.LEICA && exposure != Exposure.READY) {
                    val offset = 7.dp.toPx()
                    drawLine(meter, where + Offset(-offset, -offset), where, 1.dp.toPx())
                    drawLine(meter, where + Offset(-offset, offset), where, 1.dp.toPx())
                }
            }
        }
        focus?.let { drawCircle(brass, radius * .72f, it, style = Stroke(1.8.dp.toPx())) }
        spot?.let {
            drawRect(brass, Offset(it.left * size.width, it.top * size.height),
                Size((it.right - it.left) * size.width, (it.bottom - it.top) * size.height), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun LensBar(app: MainActivity) {
    val focalLengths = app.camera.available.sorted()
    val enabled = app.roll.phase != Phase.CAPTURING
    if (focalLengths.size > 3) {
        var choosing by remember { mutableStateOf(false) }
        Box {
            Control((app.camera.current?.let { "${it}mm" } ?: "镜头") + " ▾",
                { choosing = true }, selected = app.camera.current != null, enabled = enabled)
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                for (focal in focalLengths) {
                    DropdownMenuItem(text = { FilmText("${focal} mm 等效") }, onClick = {
                        choosing = false
                        app.chooseLens(focal)
                    })
                }
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (focal in focalLengths) {
            val selected = app.camera.current == focal
            Box(Modifier.size(43.dp, 38.dp).clip(RoundedCornerShape(5.dp))
                .background(if (selected) brass else ink).border(1.dp, if (selected) brass else line, RoundedCornerShape(5.dp))
                .semantics { contentDescription = app.text("${focal} 毫米等效") }
                .clickable(enabled = enabled, role = Role.Button) { app.chooseLens(focal) }, contentAlignment = Alignment.Center) {
                Engraving("${focal}mm", if (selected) ink else paper, 11)
            }
        }
    }
}

@Composable
private fun ShootingDeck(app: MainActivity, textures: BodyTextures?) {
    val canShoot = app.roll.phase == Phase.ARMED && app.camera.ready
    var isoDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 6.dp).clip(shape).background(deck).border(1.dp, line, shape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            val film = app.roll.film
            Box(Modifier.size(40.dp).clip(shape).clickable(enabled = app.roll.phase != Phase.CAPTURING,
                role = Role.Button, onClickLabel = app.text("选胶卷")) { app.inspectFilm(film) }) {
                FilmArt(app, film, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                FilmText(film.label, color = paper, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilmText(app.notice ?: app.camera.message ?: when (app.roll.phase) {
                    Phase.NEEDS_WIND -> "拨杆过片"
                    Phase.ARMED -> "按住对焦，松手拍"
                    Phase.CAPTURING -> "正在冲印…"
                }, color = if (canShoot) brass else muted, fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp)
            }
            LensBar(app)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(42.dp).clickable(enabled = app.saved != null, role = Role.Button, onClickLabel = app.text("查看相片")) {
                app.openLast()
            }, contentAlignment = Alignment.Center) {
                app.saved?.thumb?.let {
                    Image(it.asImageBitmap(), "最近一张", Modifier.size(34.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                } ?: Engraving("相册", muted, 9)
                Box(Modifier.align(Alignment.BottomEnd).background(ink, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp)) {
                    Engraving(String.format(Locale.US, "%02d", app.roll.frames), brass, 8)
                }
            }
        }
        HorizontalDivider(color = app.shutter.finish.rim.copy(alpha = .54f))
        MechanicalDeck(app, textures) { isoDialog = true }
    }
    if (isoDialog) IsoDialog(app) { isoDialog = false }
}

@Composable
private fun IsoDialog(app: MainActivity, dismiss: () -> Unit) {
    Dialog(dismiss) {
        Column(Modifier.clip(shape).background(deck).verticalScroll(rememberScrollState()).padding(16.dp)) {
            FilmText("胶片感光度", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            FilmText("颗粒随档位变粗。手机曝光由相机自动控制。", color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            for (row in (isoStops + app.roll.film.iso).distinct().sorted().chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (iso in row) TextButton(onClick = { app.chooseIso(iso); dismiss() }) {
                        FilmText(iso.toString(), color = if (iso == app.filmIso) brass else paper, fontFamily = mono)
                    }
                }
            }
            TextButton({ app.chooseIso(app.roll.film.iso); dismiss() }) { FilmText("使用胶卷原档 · " + app.roll.film.iso) }
        }
    }
}

@Composable
private fun FilmArt(app: MainActivity, film: Film, modifier: Modifier) {
    val prepared = app.tiles[film.key]
    val image by produceState<android.graphics.Bitmap?>(null, film.image) {
        value = withContext(Dispatchers.IO) {
            film.image?.let { path -> runCatching { app.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
    }
    val bitmap = prepared ?: image
    Box(modifier.background(Color(0xff292820)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), app.text(film.label + " · " + film.format), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else FilmText(film.label, color = paper, fontSize = 10.sp, modifier = Modifier.padding(5.dp))
    }
}

@Composable
private fun FilmDeck(app: MainActivity, onOriginal: (Film) -> Unit) {
    val current = app.picker ?: return
    var brand by remember { mutableStateOf(current.brand) }
    val shown = if (brand == "全部") app.films else app.films.filter { it.brand == brand }
    val cards = rememberLazyListState()
    LaunchedEffect(brand, app.films) {
        if (shown.isNotEmpty()) cards.scrollToItem(shown.indexOf(current).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 8.dp).clip(shape).background(deck).border(1.dp, line, shape).padding(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilmText("胶卷", color = paper, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Engraving(shown.size.toString() + " 卷", muted, 10)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (name in listOf("全部") + app.films.map(Film::brand).distinct().sorted()) {
                Control(name, { brand = name }, brand == name)
            }
        }
        LazyRow(state = cards, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = Film::key) { film ->
                val selected = film == current
                Column(Modifier.width(97.dp).clip(shape).background(ink).border(if (selected) 2.dp else 1.dp,
                    if (selected) brass else line, shape).clickable(role = Role.Button) { app.inspectFilm(film) }.padding(5.dp)) {
                    FilmArt(app, film, Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(4.dp)))
                    FilmText(film.label, color = if (selected) brass else paper, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    FilmText(film.badge, color = muted, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
        FilmText(current.label, color = paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(top = 7.dp))
        FilmText(current.type + " · " + current.format + " · " + current.iso + " · " + current.badge,
            color = muted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Control("实拍与来源", { onOriginal(current) })
            Spacer(Modifier.weight(1f))
            Control("取消", app::cancelPicker)
            Control(if (current == app.roll.film) "保留此卷" else "装卷", app::load, selected = true)
        }
    }
}

@Composable
private fun ReferenceDialog(app: MainActivity, film: Film, dismiss: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, film) {
        value = withContext(Dispatchers.IO) {
            film.reference?.let { path -> runCatching { app.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
    }
    Dialog(dismiss) {
        Column(Modifier.clip(shape).background(deck).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            FilmText(film.brand + " " + film.label, color = paper, fontWeight = FontWeight.Bold)
            bitmap?.let { Image(it.asImageBitmap(), app.text("胶卷或包装实拍"), Modifier.fillMaxWidth().heightIn(max = 290.dp)
                .padding(vertical = 7.dp), contentScale = ContentScale.Fit) }
            FilmText(film.credit, color = muted, fontSize = 12.sp)
            FilmText(if (bitmap == null) "查看厂商资料" else "抽屉封面为实拍参考重绘。", color = muted, fontSize = 11.sp)
            if (film.look != null) FilmText("使用独立制作的近似色彩曲线。", color = muted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ app.openSource(film) }) { FilmText("来源页面") }
                TextButton(dismiss) { FilmText("返回") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(app: MainActivity, dismiss: () -> Unit) {
    var category by remember { mutableIntStateOf(0) }
    Dialog(dismiss) {
        Column(Modifier.clip(shape).background(deck).padding(14.dp)) {
            FilmText("机身设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("快门", "过片", "ISO", "取景器", "成片", "语言 / Language").forEachIndexed { index, title ->
                    Control(title, { category = index }, selected = category == index)
                }
            }
            if (category == 0 || category == 1 || category == 3) {
                Toggle("锁定机身品牌", app.brandLocked, app::lockBrand)
                FilmText("快门、拨杆和取景器一起切换", color = muted, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                when (category) {
                    0 -> {
                        for (shutter in Shutter.entries) {
                            val detail = when (shutter) {
                                Shutter.LEICA -> "Leica M3 实录 · 同系旁轴参考"
                                Shutter.FM2, Shutter.AE1, Shutter.PENTAX -> "该机型真机快门"
                                else -> "模拟机械声"
                            }
                            Choice(shutter.title, detail + " · " + shutter.finish.label, app.shutter == shutter,
                                shutter.artwork) { app.chooseShutter(shutter) }
                        }
                        TextButton(app::hearShutter) { FilmText("试听快门") }
                    }
                    1 -> {
                        for (advance in Advance.entries) {
                            val detail = if (advance.coaxialShutter) "快门与过片共轴" else "独立快门与拨杆"
                            Choice(advance.title, detail, app.advance == advance, advance.artwork) {
                                app.chooseAdvance(advance)
                            }
                        }
                        TextButton(app::hearAdvance) { FilmText("试听拨杆") }
                    }
                    2 -> {
                        for (dial in DialStyle.entries) {
                            Choice(dial.title, "ASA 刻度窗", app.dial == dial, dial.artwork) { app.chooseDial(dial) }
                        }
                        TextButton(app::hearDial) { FilmText("试听 ISO 卡点") }
                    }
                    3 -> {
                        for (finder in Finder.entries) {
                            Choice(finder.title, finder.style, app.finder == finder) { app.chooseFinder(finder) }
                        }
                    }
                    4 -> {
                        app.camera.stillSize?.let { size ->
                            FilmText("镜头拍摄尺寸  ${maxOf(size.width, size.height)} × ${minOf(size.width, size.height)}",
                                color = paper, fontSize = 13.sp)
                        }
                        app.saved?.let { last ->
                            FilmText("最近成片  ${last.width} × ${last.height}", color = muted, fontSize = 12.sp)
                        }
                        Toggle("35mm 胶片齿孔", app.filmEdges, app::toggleFilmEdges)
                        FilmText("随胶卷印上片名、已核对的型号代码、冲洗标记和帧号。即显片保留原画幅。",
                            color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                        ImprintSettings(app)
                        HorizontalDivider(color = line)
                        Toggle("兼容焦段切换", app.compatibleZoom, app::toggleCompatibleZoom)
                        FilmText("使用逻辑相机变焦切换焦段，实际物理镜头由系统决定。适合物理镜头切换无效的机型。", color = muted, fontSize = 12.sp)
                    }
                    5 -> LanguageSettings(app)
                }
            }
            HorizontalDivider(color = line)
            Toggle("机械声音 · 媒体音量", app.audio, app::toggleAudio)
            Toggle("机械震感", app.haptics, app::toggleHaptic)
            if (app.haptics) {
                FilmText("震感强度", fontSize = 12.sp, color = muted)
                Slider(app.hapticStrength, app::changeHapticStrength, valueRange = .35f..1f,
                    onValueChangeFinished = app::saveHapticStrength)
            }
            Toggle("手持晃动提示", app.motionEnabled, app::toggleMotion)
            TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) { FilmText("完成") }
        }
    }
}

@Composable
internal fun Choice(label: String, detail: String, selected: Boolean, artwork: Int? = null, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = choose).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(8.dp))
        if (artwork != null) Image(painterResource(artwork), null, Modifier.size(50.dp, 36.dp), contentScale = ContentScale.Fit)
        Column {
            FilmText(label, color = paper, fontSize = 13.sp)
            FilmText(detail, color = muted, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilmText(label, Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked, changed)
    }
}

@Composable
private fun Badge(label: String, color: Color = paper) {
    Box(Modifier.background(ink.copy(alpha = .69f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) { Engraving(label, color, 9) }
}

@Composable
internal fun Control(label: String, click: () -> Unit, selected: Boolean = false, enabled: Boolean = true) {
    val style = RoundedCornerShape(6.dp)
    Box(Modifier.clip(style).background(if (selected) brass else deck).border(1.dp, if (selected) brass else line, style)
        .clickable(enabled = enabled, role = Role.Button, onClick = click).padding(horizontal = 9.dp, vertical = 7.dp)) {
        Engraving(label, if (selected) ink else if (enabled) paper else muted, 11)
    }
}

@Composable
private fun Engraving(label: String, color: Color, font: Int) {
    FilmText(label, color = color, fontFamily = mono, fontSize = font.sp, letterSpacing = .15.sp, lineHeight = (font + 2).sp)
}
