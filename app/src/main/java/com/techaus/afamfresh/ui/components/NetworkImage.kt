package com.techaus.afamfresh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.techaus.afamfresh.ui.theme.ForestSurface

/**
 * Every remote image in the app goes through here, so loading behaviour is
 * consistent instead of being configured ad hoc at each call site.
 *
 * What this fixes: the raw `AsyncImage` calls specified no placeholder and no
 * crossfade, so a cell was blank until its bitmap arrived and then snapped in
 * abruptly — the visible "popping" as you scroll a product list.
 *
 * On sizing: Coil's `AsyncImage` already resolves the decode size from the
 * composable's layout constraints, so images are not decoded at full
 * resolution into small thumbnails and no explicit size hint is required. The
 * one thing worth passing is the placeholder, below.
 */
@Composable
fun NetworkImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = ForestSurface
) {
    val context = LocalContext.current

    // Keyed on the model so scrolling does not rebuild the request every frame.
    val request = remember(model, context) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(200)
            .build()
    }

    val fallback = remember(placeholderColor) { ColorPainter(placeholderColor) }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        placeholder = fallback,
        // A broken or missing image URL keeps the same tinted block rather than
        // collapsing the layout or showing a torn-image glyph.
        error = fallback,
        fallback = fallback
    )
}
