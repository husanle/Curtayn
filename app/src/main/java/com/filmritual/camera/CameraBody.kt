package com.filmritual.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class BodyTexture { LEATHER, BRUSHED, SPIN_METAL, RESIN }

internal enum class BodyFinish(
    val label: String,
    val grip: BodyTexture,
    val trim: BodyTexture,
    val wash: Color,
    val rim: Color,
    val plate: Int?
) {
    BLACK_LACQUER("黑漆与皮纹", BodyTexture.LEATHER, BodyTexture.SPIN_METAL, Color(0xff1a1414), Color(0xff757075), R.drawable.mech_m6_plate),
    DARK_SATIN("深色缎面金属", BodyTexture.SPIN_METAL, BodyTexture.SPIN_METAL, Color(0xff141b22), Color(0xff788a99), R.drawable.mech_m6_plate),
    SILVER_SATIN("浅银缎面与皮纹", BodyTexture.LEATHER, BodyTexture.BRUSHED, Color(0xff28292a), Color(0xffa7a8a8), R.drawable.mech_fm2_plate),
    NICKEL("冷银拉丝与黑皮纹", BodyTexture.LEATHER, BodyTexture.BRUSHED, Color(0xff1a222b), Color(0xffa2b2c2), R.drawable.mech_fm2_plate),
    CHROME("暖银缎面与黑皮纹", BodyTexture.LEATHER, BodyTexture.BRUSHED, Color(0xff262019), Color(0xffb0a18e), R.drawable.mech_fm2_plate),
    BLACK_ENAMEL("石墨漆与细纹蒙皮", BodyTexture.RESIN, BodyTexture.SPIN_METAL, Color(0xff221e20), Color(0xff8e7d82), R.drawable.mech_m6_plate),
    CLASSIC_SILVER("银色金属与粗纹蒙皮", BodyTexture.LEATHER, BodyTexture.BRUSHED, Color(0xff221e1a), Color(0xffa09b90), R.drawable.mech_fm2_plate),
    MOLDED_RESIN("哑光树脂", BodyTexture.RESIN, BodyTexture.RESIN, Color(0xff272922), Color(0xff85877c), null);
}

internal val Shutter.finish get() = when (this) {
    Shutter.LEICA -> BodyFinish.BLACK_LACQUER
    Shutter.ZEISS -> BodyFinish.DARK_SATIN
    Shutter.NIKON -> BodyFinish.SILVER_SATIN
    Shutter.FM2 -> BodyFinish.NICKEL
    Shutter.CANON -> BodyFinish.CHROME
    Shutter.AE1 -> BodyFinish.BLACK_ENAMEL
    Shutter.PENTAX -> BodyFinish.CLASSIC_SILVER
    Shutter.AGFA -> BodyFinish.MOLDED_RESIN
}

internal typealias BodyTextures = Map<BodyTexture, ImageBitmap>

@Composable
internal fun rememberBodyTextures(): BodyTextures? {
    val assets = LocalContext.current.assets
    val textures by produceState<BodyTextures?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            val atlas = assets.open("materials/camera-atlas.png").use { BitmapFactory.decodeStream(it) }
            val width = atlas.width / 2
            val height = atlas.height / 2
            BodyTexture.entries.associateWith { texture ->
                val col = texture.ordinal % 2
                val row = texture.ordinal / 2
                Bitmap.createBitmap(atlas, col * width + 2, row * height + 2, width - 4, height - 4).asImageBitmap()
            }.also { atlas.recycle() }
        }
    }
    return textures
}

@Composable
internal fun CameraBody(finish: BodyFinish, textures: BodyTextures?, modifier: Modifier) {
    Crossfade(finish, modifier, animationSpec = tween(220), label = "camera body") { surface ->
        Box(Modifier.fillMaxSize()
            .background(surface.wash)
            .texture(textures?.get(surface.grip), 252)
            .drawWithCache {
                val lighting = Brush.verticalGradient(
                    0f to surface.wash.copy(alpha = .30f),
                    .58f to surface.wash.copy(alpha = .39f),
                    1f to Color.Black.copy(alpha = .37f)
                )
                onDrawBehind { drawRect(lighting) }
            }) {
            Box(Modifier.fillMaxWidth().height(113.dp)
                .texture(textures?.get(surface.trim), 390)
                .drawWithCache {
                    val shade = Brush.verticalGradient(
                        0f to surface.wash.copy(alpha = .72f),
                        .68f to surface.wash.copy(alpha = .65f),
                        1f to surface.wash.copy(alpha = .54f)
                    )
                    onDrawBehind {
                        drawRect(shade)
                        drawLine(surface.rim.copy(alpha = .48f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
                    }
                })
        }
    }
}

@Composable
internal fun ControlPlate(finish: BodyFinish, textures: BodyTextures?, modifier: Modifier) {
    Crossfade(finish, modifier, animationSpec = tween(220), label = "control plate") { surface ->
        Box(Modifier.fillMaxSize().background(surface.wash)) {
            if (surface.plate != null) {
                val leatherBand = if (surface.plate == R.drawable.mech_fm2_plate) .14f else .07f
                Image(painterResource(surface.plate), null, Modifier.matchParentSize().graphicsLayer {
                    scaleY = 1f / (1f - leatherBand)
                    translationY = -(scaleY - 1f) * size.height / 2f
                }, contentScale = ContentScale.FillBounds)
            } else {
                Box(Modifier.matchParentSize().texture(textures?.get(surface.trim), 278))
            }
            Box(Modifier.matchParentSize().drawWithCache {
                val light = Brush.verticalGradient(
                    0f to surface.wash.copy(alpha = .26f),
                    .53f to surface.rim.copy(alpha = .09f),
                    1f to surface.wash.copy(alpha = .28f)
                )
                onDrawBehind { drawRect(light) }
            })
        }
    }
}

internal fun Modifier.viewfinderBezel(finish: BodyFinish, textures: BodyTextures?): Modifier =
    clip(RoundedCornerShape(7.dp))
        .background(Color(0xff17191a))
        .texture(textures?.get(if (finish.grip == BodyTexture.RESIN) finish.grip else finish.trim), 310)
        .drawWithCache {
            val outer = Brush.verticalGradient(
                0f to finish.rim.copy(alpha = .82f),
                .44f to finish.rim.copy(alpha = .30f),
                1f to Color(0xff060708)
            )
            val bevel = Brush.verticalGradient(
                0f to Color(0xff070809),
                .62f to Color(0xff1d2225),
                1f to finish.rim.copy(alpha = .76f)
            )
            onDrawBehind {
                fun ring(insetDp: Float, radiusDp: Float, strokeDp: Float, brush: Brush) {
                    val inset = insetDp.dp.toPx()
                    drawRoundRect(brush, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
                        CornerRadius(radiusDp.dp.toPx()), style = Stroke(strokeDp.dp.toPx()))
                }
                drawRoundRect(Color.Black.copy(alpha = .46f), cornerRadius = CornerRadius(7.dp.toPx()))
                ring(.55f, 6.5f, 1f, outer)
                ring(1.5f, 5.5f, .8f, bevel)
            }
        }

private fun Modifier.texture(bitmap: ImageBitmap?, widthDp: Int): Modifier = if (bitmap == null) this else drawWithCache {
    val brush = ShaderBrush(ImageShader(bitmap, TileMode.Mirror, TileMode.Mirror))
    val zoom = widthDp.dp.toPx() / bitmap.width
    onDrawBehind {
        scale(zoom, zoom, pivot = Offset.Zero) {
            drawRect(brush, size = Size(size.width / zoom, size.height / zoom))
        }
    }
}
