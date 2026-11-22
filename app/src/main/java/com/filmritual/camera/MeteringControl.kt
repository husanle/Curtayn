package com.filmritual.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun MeteringControl(app: MainActivity) {
    var showName by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        if (taps > 0) { showName = true; delay(2100); showName = false }
    }
    Column(horizontalAlignment = Alignment.End) {
        if (showName) {
            Column(Modifier.padding(bottom = 5.dp).clip(RoundedCornerShape(4.dp))
                .background(ink.copy(alpha = .88f)).padding(8.dp).widthIn(max = 220.dp)) {
                FilmText(app.meteringMode.title, color = Color.White, fontSize = 12.sp)
                if (app.meteringMode == MeteringMode.SPOT)
                    FilmText("轻点画面选择测光点", color = muted, fontSize = 10.sp)
                if (app.camera.metering == MeteringStatus.SYSTEM)
                    FilmText("当前由设备自动测光", color = brass, fontSize = 10.sp)
                else if (app.camera.metering == MeteringStatus.WAITING)
                    FilmText("测光调整中…", color = muted, fontSize = 10.sp)
                else if (app.camera.metering == MeteringStatus.COMPENSATED)
                    FilmText("兼容测光", color = muted, fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ev = app.camera.meteringEv
            if (app.camera.metering == MeteringStatus.SYSTEM)
                FilmText("!", color = brass, modifier = Modifier.padding(end = 4.dp))
            else if (ev != null) FilmText(String.format(Locale.US, "%+.1f EV", ev), color = Color.White,
                fontSize = 10.sp, modifier = Modifier.padding(end = 5.dp))
            val label = app.text(app.meteringMode.title) + ", " + app.text("切换测光方式")
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(3.dp))
                .semantics { contentDescription = label }
                .clickable(enabled = app.roll.phase != Phase.CAPTURING, role = Role.Button,
                    onClickLabel = app.text("切换测光方式")) {
                    val modes = MeteringMode.entries
                    app.chooseMetering(modes[(modes.indexOf(app.meteringMode) + 1) % modes.size])
                    taps++
                }, contentAlignment = Alignment.Center) {
                MeteringSymbol(app.meteringMode, Modifier.size(30.dp, 24.dp))
            }
        }
    }
}

@Composable
internal fun MeteringSymbol(mode: MeteringMode, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sx = size.width / 32f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val stroke = Stroke(1.8f * sy, cap = StrokeCap.Round)
        val brackets = Path().apply {
            moveTo(12f * sx, 3f * sy); lineTo(5f * sx, 3f * sy)
            quadraticTo(3f * sx, 3f * sy, 3f * sx, 5f * sy)
            lineTo(3f * sx, 19f * sy); quadraticTo(3f * sx, 21f * sy, 5f * sx, 21f * sy)
            lineTo(12f * sx, 21f * sy)
            moveTo(20f * sx, 3f * sy); lineTo(27f * sx, 3f * sy)
            quadraticTo(29f * sx, 3f * sy, 29f * sx, 5f * sy)
            lineTo(29f * sx, 19f * sy); quadraticTo(29f * sx, 21f * sy, 27f * sx, 21f * sy)
            lineTo(20f * sx, 21f * sy)
        }
        drawPath(brackets, Color.Black.copy(alpha = .65f), style = Stroke(3.8f * sy, cap = StrokeCap.Round))
        drawPath(brackets, Color.White, style = stroke)
        when (mode) {
            MeteringMode.AVERAGE -> Unit
            MeteringMode.CENTER -> drawCircle(Color.White, 5f * sy, p(16f, 12f), style = stroke)
            MeteringMode.SPOT -> drawCircle(Color.White, 2.4f * sy, p(16f, 12f))
            MeteringMode.WEIGHTED -> {
                drawCircle(Color.White, 5f * sy, p(16f, 12f), style = stroke)
                drawCircle(Color.White, 1.5f * sy, p(16f, 12f))
                drawLine(Color.White, p(16f, 3f), p(16f, 5f), 1.6f * sy, StrokeCap.Round)
                drawLine(Color.White, p(16f, 19f), p(16f, 21f), 1.6f * sy, StrokeCap.Round)
                drawLine(Color.White, p(7f, 12f), p(9f, 12f), 1.6f * sy, StrokeCap.Round)
                drawLine(Color.White, p(23f, 12f), p(25f, 12f), 1.6f * sy, StrokeCap.Round)
            }
        }
    }
}
