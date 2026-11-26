package com.musico.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

val LocalHasBackgroundImage = staticCompositionLocalOf { false }

@Composable
fun musicoScaffoldContainerColor(): Color =
    if (LocalHasBackgroundImage.current) Color.Transparent else MaterialTheme.colorScheme.background

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun musicoTopAppBarColors(): TopAppBarColors {
    val hasBg = LocalHasBackgroundImage.current
    return if (hasBg) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
        )
    } else {
        TopAppBarDefaults.topAppBarColors()
    }
}

@Composable
fun musicoListItemColors(): ListItemColors {
    val hasBg = LocalHasBackgroundImage.current
    return if (hasBg) {
        ListItemDefaults.colors(containerColor = Color.Transparent)
    } else {
        ListItemDefaults.colors()
    }
}

@Composable
fun rememberSampledBitmap(
    file: File?,
    maxDimension: Int = 2048,
    blurRadiusDp: Float = 0f,
): ImageBitmap? {
    val path = file?.absolutePath
    val lastModified = file?.lastModified() ?: 0L
    val clampedBlur = clampBackgroundImageBlurDp(blurRadiusDp)
    val needsSoftwareBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && clampedBlur > 0f
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        path,
        lastModified,
        maxDimension,
        if (needsSoftwareBlur) clampedBlur.roundToInt() else 0,
    ) {
        value = if (file != null && file.isFile) {
            withContext(Dispatchers.IO) {
                val targetDim = if (needsSoftwareBlur) 220 else maxDimension
                val decoded = decodeSampledRawBitmap(file, targetDim) ?: return@withContext null
                if (needsSoftwareBlur) {
                    val radiusPx = (clampedBlur / 2f).roundToInt().coerceIn(1, 18)
                    val blurred = fastBlurBitmap(decoded, radiusPx)
                    if (blurred !== decoded) decoded.recycle()
                    blurred.asImageBitmap()
                } else {
                    decoded.asImageBitmap()
                }
            }
        } else {
            null
        }
    }
    return bitmap
}

@Composable
fun ThemeBackgroundLayer(
    imageFile: File?,
    imageOpacity: Float,
    imageBlurDp: Float = AppearanceSettings.DEFAULT_BACKGROUND_IMAGE_BLUR_DP,
    modifier: Modifier = Modifier,
) {
    val clampedBlur = clampBackgroundImageBlurDp(imageBlurDp)
    val bitmap = rememberSampledBitmap(file = imageFile, blurRadiusDp = clampedBlur)
    val clampedOpacity = clampBackgroundImageOpacity(imageOpacity)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (bitmap != null) {
            val imageModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && clampedBlur > 0f) {
                Modifier.fillMaxSize().blur(clampedBlur.dp)
            } else {
                Modifier.fillMaxSize()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.background.copy(
                            alpha = (1f - clampedOpacity).coerceIn(0f, 1f)
                        )
                    )
            )
        }
    }
}

fun fastBlurBitmap(source: Bitmap, radius: Int): Bitmap {
    if (radius <= 0) return source
    val w = source.width
    val h = source.height
    if (w <= 1 || h <= 1) return source

    val scaleTarget = 160
    val longest = max(w, h)
    val working = if (longest > scaleTarget) {
        val factor = scaleTarget.toFloat() / longest.toFloat()
        Bitmap.createScaledBitmap(
            source,
            max(1, (w * factor).roundToInt()),
            max(1, (h * factor).roundToInt()),
            true,
        )
    } else {
        source.copy(Bitmap.Config.ARGB_8888, true)
    }

    val ww = working.width
    val hh = working.height
    val pixels = IntArray(ww * hh)
    val temp = IntArray(ww * hh)
    working.getPixels(pixels, 0, ww, 0, 0, ww, hh)

    val r = radius.coerceIn(1, 18)
    repeat(2) {
        // Horizontal box pass
        for (y in 0 until hh) {
            val rowOffset = y * ww
            for (x in 0 until ww) {
                var aSum = 0
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                val x0 = max(0, x - r)
                val x1 = minOf(ww - 1, x + r)
                for (nx in x0..x1) {
                    val p = pixels[rowOffset + nx]
                    aSum += (p ushr 24) and 0xFF
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                    count++
                }
                temp[rowOffset + x] =
                    ((aSum / count) shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
            }
        }
        // Vertical box pass
        for (x in 0 until ww) {
            for (y in 0 until hh) {
                var aSum = 0
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                val y0 = max(0, y - r)
                val y1 = minOf(hh - 1, y + r)
                for (ny in y0..y1) {
                    val p = temp[ny * ww + x]
                    aSum += (p ushr 24) and 0xFF
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                    count++
                }
                pixels[y * ww + x] =
                    ((aSum / count) shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
            }
        }
    }

    working.setPixels(pixels, 0, ww, 0, 0, ww, hh)
    return working
}

private fun decodeSampledRawBitmap(file: File, maxDimension: Int): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var inSampleSize = 1
        while (width / inSampleSize > maxDimension || height / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }.getOrNull()
}
