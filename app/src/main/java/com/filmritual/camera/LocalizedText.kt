package com.filmritual.camera

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

internal val LocalTranslator = staticCompositionLocalOf<(String) -> String> { { it } }

@Composable
internal fun FilmText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true) {
    Text(LocalTranslator.current(text), modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, lineHeight = lineHeight, maxLines = maxLines, overflow = overflow, softWrap = softWrap)
}
