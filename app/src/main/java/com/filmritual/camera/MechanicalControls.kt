package com.filmritual.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

private data class LeverSprite(val drawable: Int, val ratio: Float, val hub: Offset)
private data class DialSprite(val drawable: Int, val window: Offset, val textTilt: Float)

private val Advance.sprite get() = when (this) {
    Advance.NIKON -> LeverSprite(R.drawable.mech_fm2_advance, 2.14f, Offset(.835f, .365f))
    Advance.NIKON_SP -> LeverSprite(R.drawable.mech_sp_advance, 2f, Offset(.769f, .457f))
    Advance.LEICA -> LeverSprite(R.drawable.mech_m6_advance, 2.406f, Offset(.84f, .37f))
    Advance.ZEISS -> LeverSprite(R.drawable.mech_zeiss_advance, 2.983f, Offset(.84f, .48f))
    Advance.CANON -> LeverSprite(R.drawable.mech_canon_advance, 2.732f, Offset(.84f, .455f))
    Advance.CANON_AE1 -> LeverSprite(R.drawable.mech_ae1_advance, 1881f / 836f, Offset(.789f, .402f))
    Advance.PENTAX -> LeverSprite(R.drawable.mech_pentax_advance, 1.896f, Offset(.79f, .40f))
    Advance.AGFA -> LeverSprite(R.drawable.mech_agfa_advance, 1903f / 826f, Offset(.765f, .463f))
}

private val DialStyle.sprite get() = when (this) {
    DialStyle.BLACK -> DialSprite(R.drawable.mech_fm2_dial, Offset(.28f, .345f), 23f)
    DialStyle.SILVER -> DialSprite(R.drawable.mech_silver_dial, Offset(.205f, .334f), 13f)
    DialStyle.RESIN -> DialSprite(R.drawable.mech_resin_dial, Offset(.252f, .324f), 14f)
}

internal val Advance.artwork get() = sprite.drawable
internal val DialStyle.artwork get() = sprite.drawable
internal val Shutter.artwork get() = when (this) {
    Shutter.FM2 -> R.drawable.mech_fm2_shutter
    Shutter.LEICA -> R.drawable.mech_m6_shutter
    Shutter.ZEISS -> R.drawable.mech_zeiss_shutter_top
    Shutter.NIKON -> R.drawable.mech_sp_shutter_top
    Shutter.CANON -> R.drawable.mech_canon_shutter
    Shutter.AE1 -> R.drawable.mech_ae1_shutter
    Shutter.PENTAX -> R.drawable.mech_pentax_shutter
    Shutter.AGFA -> R.drawable.mech_agfa_shutter
}

private fun turn(from: Offset, to: Offset, pivot: Offset): Float {
    val a = from - pivot
    val b = to - pivot
    return (atan2(a.x * b.y - a.y * b.x, a.x * b.x + a.y * b.y) * 180 / PI).toFloat()
}

@Composable
internal fun MechanicalDeck(app: MainActivity, textures: BodyTextures?, openStops: () -> Unit) {
    val phase = app.roll.phase
    val style = app.advance
    val lever = remember(style) { style.sprite }
    val enabled = app.camera.ready
    val canWind = enabled && phase == Phase.NEEDS_WIND
    val layout = remember(style, app.shutter) { MechanicalLayout(style, app.shutter) }
    var paid by remember(style, app.roll.film, app.roll.frames, phase) { mutableFloatStateOf(0f) }
    var deflection by remember(style, app.roll.film, app.roll.frames, phase) { mutableFloatStateOf(0f) }
    val swing by animateFloatAsState(
        targetValue = -deflection * style.sweep,
        animationSpec = if (deflection == 0f) spring(dampingRatio = .77f, stiffness = 480f) else tween(25),
        label = "advance sweep"
    )

    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(layout.aspect).clip(RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp))
            .pointerInput(canWind, layout) {
                if (!canWind) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (size.width == 0 || !layout.canStartWinding(down.position.x / size.width, down.position.y / size.width)) {
                        return@awaitEachGesture
                    }
                    val axis = Offset(size.width * layout.pivotX, size.width * layout.pivotY)
                    down.consume()
                    val id = down.id
                    val starting = paid
                    var tooth = (starting * style.teeth).toInt()
                    var stroke = 0f
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                paid = (starting + stroke).coerceAtMost(1f)
                                change.consume()
                                if (paid >= .975f) app.wind()
                                break
                            }
                            val arc = -turn(change.previousPosition, change.position, axis) / style.sweep
                            val pull = (change.position.x - change.previousPosition.x) / (size.width * .24f)
                            val movement = if ((change.previousPosition - axis).getDistance() < size.width * .085f || abs(arc) > .3f) {
                                pull
                            } else if (pull > 0f) max(pull, arc) else arc
                            stroke = (stroke + movement).coerceIn(0f, 1f - starting)
                            deflection = stroke
                            val passed = ((starting + stroke + .012f) * style.teeth).toInt().coerceAtMost(style.teeth - 1)
                            if (passed > tooth) {
                                app.windTick()
                                tooth = passed
                            }
                            change.consume()
                        }
                    } finally {
                        deflection = 0f
                    }
                }
            }
    ) {
        ControlPlate(app.shutter.finish, textures, Modifier.matchParentSize())

        val dialWidth = maxWidth * layout.dialDiameter
        IsoDial(
            app, dialWidth, openStops,
            Modifier.offset(maxWidth * layout.dialX - dialWidth / 2, maxWidth * layout.dialY - dialWidth / 2)
        )

        val leverWidth = maxWidth * layout.leverWidth
        val leverHeight = leverWidth / lever.ratio
        Image(
            painterResource(lever.drawable), null,
            Modifier.offset(
                maxWidth * layout.pivotX - leverWidth * lever.hub.x,
                maxWidth * layout.pivotY - leverHeight * lever.hub.y
            ).size(leverWidth, leverHeight).graphicsLayer {
                transformOrigin = TransformOrigin(lever.hub.x, lever.hub.y)
                rotationZ = swing
            }.semantics {
                role = Role.Button
                contentDescription = app.text("过片拨杆 " + style.title)
                if (canWind) onClick(app.text("过片")) { app.wind(); true } else disabled()
            },
            contentScale = ContentScale.FillBounds
        )

        val releaseWidth = maxWidth * layout.shutterDiameter
        MechanicalShutter(app, enabled && phase == Phase.ARMED,
            Modifier.offset(maxWidth * layout.shutterX - releaseWidth / 2, maxWidth * layout.shutterY - releaseWidth / 2)
                .size(releaseWidth))
    }
}

@Composable
private fun MechanicalShutter(app: MainActivity, enabled: Boolean, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val depression by animateFloatAsState(if (pressed) .94f else 1f, spring(stiffness = 650f), label = "shutter stroke")
    val style = app.shutter
    val artScale = when (style) {
        Shutter.NIKON -> 1.29f
        Shutter.ZEISS -> 1.08f
        else -> 1f
    }
    Image(
        painterResource(style.artwork), null,
        modifier.clip(CircleShape).semantics {
            contentDescription = app.text("快门 ") + style.title
            role = Role.Button
            if (enabled) onClick(app.text("拍照")) { app.preFocus(); app.shoot(); true } else disabled()
        }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                pressed = true
                app.preFocus()
                try {
                    val up = waitForUpOrCancellation()
                    if (up != null && up.position.x in 0f..size.width.toFloat() && up.position.y in 0f..size.height.toFloat()) {
                        up.consume()
                        app.shoot()
                    }
                } finally {
                    pressed = false
                }
            }
        }.graphicsLayer {
            scaleX = depression * artScale
            scaleY = depression * artScale
        },
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun IsoDial(app: MainActivity, diameter: Dp, openStops: () -> Unit, modifier: Modifier) {
    val active = app.roll.phase != Phase.CAPTURING
    val spec = remember(app.dial) { app.dial.sprite }
    val digitSize = with(LocalDensity.current) { (diameter * if (app.filmIso >= 1000) .098f else .131f).toSp() }
    var angle by remember { mutableFloatStateOf(0f) }
    val shownAngle by animateFloatAsState(angle, spring(dampingRatio = .79f, stiffness = 530f), label = "ASA turn")
    Box(modifier.size(diameter).clip(CircleShape)
        .semantics {
            role = Role.Button
            contentDescription = app.text("颗粒感光度 ISO ") + app.filmIso
            if (active) onClick(app.text("选择 ISO 档位")) { openStops(); true } else disabled()
        }
        .pointerInput(active) {
            if (active) detectTapGestures(onTap = { openStops() })
        }
        .pointerInput(active) {
            if (!active) return@pointerInput
            var rotation = 0f
            var travel = 0f
            detectDragGestures(
                onDragStart = { rotation = 0f; travel = 0f },
                onDragEnd = { angle = 0f },
                onDragCancel = { angle = 0f }
            ) { change, drag ->
                change.consume()
                val axis = Offset(size.width * .5f, size.height * .5f)
                val motion = if ((change.previousPosition - axis).getDistance() < size.width * .18f) {
                    drag.x * 180f / size.width
                } else {
                    turn(change.previousPosition, change.position, axis).coerceIn(-47f, 47f)
                }
                rotation += motion
                travel += motion
                angle = (travel * .43f).coerceIn(-23f, 23f)
                while (abs(rotation) >= 23f) {
                    val direction = if (rotation > 0f) 1 else -1
                    app.stepIso(direction)
                    rotation -= direction * 23f
                }
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = shownAngle }) {
            Image(painterResource(spec.drawable), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Box(Modifier.offset(diameter * (spec.window.x - .124f), diameter * (spec.window.y - .073f))
                .size(diameter * .248f, diameter * .146f).graphicsLayer { rotationZ = spec.textTilt },
                contentAlignment = Alignment.Center) {
                FilmText(app.filmIso.toString(), color = Color(0xffe3e0d6), fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = digitSize,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}
