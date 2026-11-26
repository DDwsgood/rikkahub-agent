package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import me.rerere.rikkahub.ui.components.message.RikkaWidgetCard
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.utils.openUrl

/**
 * Unified two-layer glassmorphic renderer for assistant messages:
 * mixes standard Markdown, Layer 1 native glassmorphic rich blocks (KPI, MediaCard,
 * Gallery, LinkCard, VideoCard, Badges, Citations), and Layer 2 sandboxed `rikka-ui` widgets.
 */
@Composable
internal fun RichMarkdownContent(
    content: String,
    loading: Boolean,
    allowMediaQueryNetwork: Boolean,
    onClickCitation: (String) -> Unit,
    modifier: Modifier = Modifier,
    renderMarkdown: @Composable (String) -> Unit,
) {
    val blocks = remember(content, loading) {
        RichContentParser.parse(content, streaming = loading)
    }

    if (blocks.size == 1 && blocks[0] is RichBlock.Markdown) {
        renderMarkdown((blocks[0] as RichBlock.Markdown).text)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is RichBlock.Markdown -> renderMarkdown(block.text)
                is RichBlock.Widget -> RikkaWidgetCard(
                    html = block.html,
                    complete = block.complete,
                )
                is RichBlock.KpiGrid -> GlassKpiGrid(items = block.items)
                is RichBlock.MediaCard -> GlassMediaCard(
                    card = block,
                    allowNetwork = allowMediaQueryNetwork,
                    onClickCitation = onClickCitation,
                    renderMarkdown = renderMarkdown,
                )
                is RichBlock.Gallery -> GlassGallery(
                    photos = block.photos,
                    allowNetwork = allowMediaQueryNetwork,
                )
                is RichBlock.LinkCard -> GlassLinkCard(card = block)
                is RichBlock.VideoCard -> GlassVideoCard(card = block)
            }
        }
    }
}

@Composable
private fun glassSurfaceColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.065f)
    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)

@Composable
private fun glassBorderColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.13f)
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

@Composable
private fun glassSubSurfaceColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.05f)
    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)

@Composable
private fun GlassKpiGrid(items: List<KpiItem>) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val rows = remember(items) { items.chunked(2) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { kpi ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassSurfaceColor(dark))
                            .border(1.dp, glassBorderColor(dark), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (kpi.label.isNotEmpty()) {
                            Text(
                                text = kpi.label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = kpi.value,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (kpi.sub.isNotEmpty()) {
                            Text(
                                text = kpi.sub,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GlassMediaCard(
    card: RichBlock.MediaCard,
    allowNetwork: Boolean,
    onClickCitation: (String) -> Unit,
    renderMarkdown: @Composable (String) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val context = LocalContext.current
    var resolvedImg by remember(card.img, card.query) { mutableStateOf(card.img) }
    var imageFailed by remember(resolvedImg) { mutableStateOf(false) }

    LaunchedEffect(card.img, card.query, allowNetwork) {
        if (resolvedImg.isEmpty() && card.query.isNotEmpty() && allowNetwork) {
            val found = MediaQueryResolver.resolveImage(context, card.query, allowNetwork = true)
            if (!found.isNullOrEmpty()) {
                resolvedImg = found
                imageFailed = false
            }
        }
    }

    val aspectRatio = remember(card.ratio) {
        when (card.ratio.trim()) {
            "2:3" -> 2f / 3f
            "1:1" -> 1f
            "16:9" -> 16f / 9f
            else -> 4f / 3f
        }
    }
    val hasImage = resolvedImg.isNotEmpty() && !imageFailed
    val isPortrait = aspectRatio < 0.9f
    val showFooterSource = card.source.isNotEmpty() &&
        !card.body.contains("citation,${card.source}", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(glassSurfaceColor(dark))
            .border(1.dp, glassBorderColor(dark), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasImage && !isPortrait && aspectRatio >= 1.5f) {
            // Wide 16:9 hero banner on top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(glassSubSurfaceColor(dark)),
            ) {
                ZoomableAsyncImage(
                    model = resolvedImg,
                    contentDescription = card.title.takeIf { it.isNotEmpty() },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            MediaCardTextColumn(
                card = card,
                dark = dark,
                showFooterSource = showFooterSource,
                onOpenHref = { if (card.href.isNotEmpty()) context.openUrl(card.href) },
                renderMarkdown = renderMarkdown,
            )
        } else {
            // Cradle-style left-image + right-text editorial row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (hasImage) {
                    val imgWidth = if (isPortrait) 104.dp else 112.dp
                    Box(
                        modifier = Modifier
                            .width(imgWidth)
                            .aspectRatio(aspectRatio)
                            .clip(RoundedCornerShape(12.dp))
                            .background(glassSubSurfaceColor(dark)),
                    ) {
                        AsyncImage(
                            model = resolvedImg,
                            contentDescription = card.title.takeIf { it.isNotEmpty() },
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (card.href.isNotEmpty()) context.openUrl(card.href)
                                },
                            contentScale = ContentScale.Crop,
                            onState = { state ->
                                if (state is AsyncImagePainter.State.Error) {
                                    imageFailed = true
                                }
                            },
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    MediaCardTextColumn(
                        card = card,
                        dark = dark,
                        showFooterSource = showFooterSource,
                        onOpenHref = { if (card.href.isNotEmpty()) context.openUrl(card.href) },
                        renderMarkdown = renderMarkdown,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCardTextColumn(
    card: RichBlock.MediaCard,
    dark: Boolean,
    showFooterSource: Boolean,
    onOpenHref: () -> Unit,
    renderMarkdown: @Composable (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (card.title.isNotEmpty()) {
            Text(
                text = card.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = if (card.href.isNotEmpty()) TextDecoration.Underline else TextDecoration.None,
                modifier = if (card.href.isNotEmpty()) Modifier.clickable(onClick = onOpenHref) else Modifier,
            )
        }
        if (card.subtitle.isNotEmpty()) {
            Text(
                text = card.subtitle,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (card.body.isNotEmpty()) {
            renderMarkdown(card.body)
        }
        if (showFooterSource) {
            Spacer(modifier = Modifier.height(2.dp))
            GlassSourcePill(
                domain = card.source,
                url = card.href.ifEmpty { "https://${card.source}" },
                dark = dark,
                onClick = onOpenHref,
            )
        }
    }
}

@Composable
private fun GlassGallery(
    photos: List<GalleryPhoto>,
    allowNetwork: Boolean,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val context = LocalContext.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(photos) { photo ->
            var resolvedSrc by remember(photo.src, photo.query) { mutableStateOf(photo.src) }
            var failed by remember(resolvedSrc) { mutableStateOf(false) }

            LaunchedEffect(photo.src, photo.query, allowNetwork) {
                if (resolvedSrc.isEmpty() && photo.query.isNotEmpty() && allowNetwork) {
                    val found = MediaQueryResolver.resolveImage(context, photo.query, allowNetwork = true)
                    if (!found.isNullOrEmpty()) {
                        resolvedSrc = found
                        failed = false
                    }
                }
            }

            if (resolvedSrc.isNotEmpty() && !failed) {
                Column(
                    modifier = Modifier
                        .width(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(glassSurfaceColor(dark))
                        .border(1.dp, glassBorderColor(dark), RoundedCornerShape(14.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .background(glassSubSurfaceColor(dark)),
                    ) {
                        ZoomableAsyncImage(
                            model = resolvedSrc,
                            contentDescription = photo.caption.takeIf { it.isNotEmpty() },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (photo.caption.isNotEmpty() || photo.source.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (photo.caption.isNotEmpty()) {
                                Text(
                                    text = photo.caption,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (photo.source.isNotEmpty()) {
                                Text(
                                    text = photo.source,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassLinkCard(card: RichBlock.LinkCard) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val context = LocalContext.current
    val domain = remember(card.url) { RichContentParser.extractDomain(card.url) }
    var thumbFailed by remember(card.img) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceColor(dark))
            .border(1.dp, glassBorderColor(dark), RoundedCornerShape(14.dp))
            .clickable { context.openUrl(card.url) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Favicon(url = card.url, modifier = Modifier.size(16.dp))
                Text(
                    text = domain.ifEmpty { card.url },
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = card.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.desc.isNotEmpty()) {
                Text(
                    text = card.desc,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card.img.isNotEmpty() && !thumbFailed) {
            AsyncImage(
                model = card.img,
                contentDescription = card.title,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(glassSubSurfaceColor(dark)),
                contentScale = ContentScale.Crop,
                onState = { if (it is AsyncImagePainter.State.Error) thumbFailed = true },
            )
        }
    }
}

@Composable
private fun GlassVideoCard(card: RichBlock.VideoCard) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val context = LocalContext.current
    var thumbFailed by remember(card.thumb) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(glassSurfaceColor(dark))
            .border(1.dp, glassBorderColor(dark), RoundedCornerShape(16.dp))
            .clickable { context.openUrl(card.url) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF13263E),
                            Color(0xFF1B2340),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (card.thumb.isNotEmpty() && !thumbFailed) {
                AsyncImage(
                    model = card.thumb,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onState = { if (it is AsyncImagePainter.State.Error) thumbFailed = true },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f)),
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.25f, size.height * 0.12f)
                        lineTo(size.width * 0.88f, size.height * 0.5f)
                        lineTo(size.width * 0.25f, size.height * 0.88f)
                        close()
                    }
                    drawPath(path, color = Color.White)
                }
            }
            if (card.source.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = card.source,
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = card.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = RichContentParser.extractDomain(card.url).ifEmpty { card.source },
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GlassSourcePill(
    domain: String,
    url: String,
    dark: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (dark) Color.White.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
            )
            .border(1.dp, glassBorderColor(dark), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Favicon(url = url, modifier = Modifier.size(13.dp))
        Text(
            text = domain,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
