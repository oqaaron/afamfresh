package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.R
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.DividerGray
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.ForestSurface
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted
import com.techaus.afamfresh.viewmodel.ProductViewModel

private data class OnboardingSlide(
    val icon: ImageVector,
    val title: String,
    val body: String
)

// ⚠️ PLACEHOLDER COPY. The mockup only showed one example slide's text
// ("99.9% Germ-Free Freshness"); the dots implied three total slides but
// only that one was visible. The other two here are written to fit
// AfamFresh's own positioning (matches SplashScreen.kt's existing "Fresh
// produce delivered" tagline) but should be reviewed/replaced with real
// marketing copy before shipping.
//
// `icon` is the fallback shown only if a real product photo isn't
// available yet (catalogue still loading, or empty for a signed-out user —
// see the loadProducts() note on OnboardingScreen below) — never the
// primary visual once the catalogue has loaded.
private val ONBOARDING_SLIDES = listOf(
    OnboardingSlide(
        icon = Icons.Default.Spa,
        title = "99.9% Germ-Free Freshness",
        body = "Every fruit and vegetable is washed and handled with care, so what arrives is as fresh as what left the farm."
    ),
    OnboardingSlide(
        icon = Icons.Default.Bolt,
        title = "Delivered fast, every time",
        body = "Track your order in real time, from the moment it's packed to the moment it's at your door."
    ),
    OnboardingSlide(
        icon = Icons.Default.Favorite,
        title = "Quality you can trust",
        body = "Sourced from vendors we know, delivered by riders you can track — fresh produce, done right."
    )
)

/**
 * Shown once, gated by [com.techaus.afamfresh.utils.OnboardingPrefs] in
 * MainActivity — not on every launch. Page 0 is the brand welcome (reuses
 * SplashScreen.kt's exact brand-mark treatment for consistency between the
 * two); pages 1..N are the value-prop slides with dot pagination.
 *
 * Tap-to-advance only, no swipe — HorizontalPager would need Compose
 * Foundation 1.4+, which I can't confirm this project has without a
 * build.gradle/version-catalog check, so this uses plain index state
 * instead to guarantee it compiles regardless of version. Swapping in
 * HorizontalPager later for swipe gestures is a self-contained upgrade.
 *
 * @param productViewModel Reused, not a fresh instance — MainActivity passes
 *        the same one it hands to MainScreen/HomeScreen. Onboarding shows
 *        BEFORE login, though, so [loadProducts] here may run against a
 *        signed-out session. If the products endpoint requires auth, this
 *        comes back empty and every slide falls back to its icon — never a
 *        blank circle, but worth confirming the endpoint is actually public
 *        if you want real photos showing on a genuinely first-ever launch.
 */
@Composable
fun OnboardingScreen(productViewModel: ProductViewModel, onDone: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    val lastPage = ONBOARDING_SLIDES.size

    val products by productViewModel.products.collectAsState()

    // Same call HomeScreen makes. Guarded on emptiness rather than always
    // firing, so revisiting this composable (e.g. a config change before
    // markOnboardingSeen() commits) doesn't re-request a catalogue that's
    // already loaded.
    LaunchedEffect(Unit) {
        if (products.isEmpty()) productViewModel.loadProducts()
    }

    // One real photo per slide, in whatever order the catalogue returns —
    // no attempt to match a product to a slide's theme (e.g. a vegetable
    // photo specifically for the "freshness" slide), since that would
    // depend on category names actually present in the data and silently
    // fall back to the icon for any slide whose match fails.
    val slideImageUrls = remember(products) {
        products.mapNotNull { it.imageUrl?.takeIf { url -> url.isNotBlank() } }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // Skip — not in the mockup, but a near-universal onboarding
        // courtesy so someone who's seen this before (e.g. after a
        // reinstall) is never forced through every slide to reach login.
        if (page > 0) {
            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Text("Skip", color = InkMuted, fontWeight = FontWeight.SemiBold)
            }
        }

        when (page) {
            0 -> WelcomePage(onNext = { page = 1 })
            else -> {
                val slide = ONBOARDING_SLIDES[page - 1]
                SlidePage(
                    slide = slide,
                    imageUrl = slideImageUrls.getOrNull(page - 1),
                    pageIndex = page - 1,
                    totalSlides = ONBOARDING_SLIDES.size,
                    onNext = { if (page < lastPage) page += 1 else onDone() }
                )
            }
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Same brand-mark treatment as SplashScreen.kt (mark on a plain
        // disc) so the two screens feel like one continuous sequence rather
        // than two different logo styles back to back.
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(ForestSurface),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.brand_mark),
                contentDescription = "AfamFresh",
                modifier = Modifier.size(84.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("AfamFresh", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            "Fresh produce delivered",
            fontSize = 14.sp,
            color = InkMuted,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            "Let's Get started",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        IconButton(
            onClick = onNext,
            modifier = Modifier.size(56.dp).clip(CircleShape).background(Forest)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Get started", tint = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SlidePage(
    slide: OnboardingSlide,
    imageUrl: String?,
    pageIndex: Int,
    totalSlides: Int,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(ForestSurface),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                NetworkImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(slide.icon, contentDescription = null, tint = Forest, modifier = Modifier.size(72.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            slide.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            slide.body,
            fontSize = 14.sp,
            color = InkMuted,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(totalSlides) { i ->
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(if (i == pageIndex) 20.dp else 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == pageIndex) Forest else DividerGray)
                    )
                }
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(Forest)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
