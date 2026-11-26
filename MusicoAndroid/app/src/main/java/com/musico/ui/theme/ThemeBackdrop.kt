package com.musico.ui.theme

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
): ImageBitmap? {
    val path = file?.absolutePath
    val lastModified = file?.lastModified() ?: 0L
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, lastModified, maxDimension) {
        value = if (file != null && file.isFile) {
            withContext(Dispatchers.IO) {
                decodeSampledBitmap(file, maxDimension)
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
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberSampledBitmap(file = imageFile)
    val clampedOpacity = clampBackgroundImageOpacity(imageOpacity)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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

private fun decodeSampledBitmap(file: File, maxDimension: Int): ImageBitmap? {
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
        BitmapFactory.decodeFile(file.absolutePath, decodeOptions)?.asImageBitmap()
    }.getOrNull()
}
