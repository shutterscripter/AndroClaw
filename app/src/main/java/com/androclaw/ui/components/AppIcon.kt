package com.androclaw.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders the AndroClaw logo from assets/app_logo.png.
 * Clips to a circle and crops to fill — no gaps, no black corners.
 */
@Composable
fun AppIcon(size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember {
        context.assets.open("app_logo.png").use { stream ->
            BitmapFactory.decodeStream(stream).asImageBitmap()
        }
    }

    Image(
        bitmap = bitmap,
        contentDescription = "AndroClaw",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    )
}
