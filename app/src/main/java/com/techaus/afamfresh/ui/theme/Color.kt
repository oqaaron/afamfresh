package com.techaus.afamfresh.ui.theme

import androidx.compose.ui.graphics.Color

// ⚠️ INFERRED: These names (Forest, Tomato, InkMuted, Cream) were already used
// in your existing LoginScreen.kt but never defined in what was shared with me.
// If app/src/main/java/com/techaus/afamfresh/ui/theme/Color.kt already exists
// in your repo, use YOUR real values — don't let this silently overwrite them.
// Values below are picked to match the reference mockup's vibrant green look.

val Forest = Color(0xFF1B7A3D)        // primary green — buttons, active states, headings
val ForestLight = Color(0xFF4CAF50)   // lighter green — mockup's card/badge accents
val ForestSurface = Color(0xFFE8F5E9) // pale green card background (e.g. selected filter pill)

val Tomato = Color(0xFFE53935)        // error / destructive text (already used for login errors)
val InkMuted = Color(0xFF8A8F98)      // secondary/muted text (subtitles, timestamps)
val Ink = Color(0xFF1A1D1F)           // primary text/headings

val Cream = Color(0xFFF2F3F0)         // app background — deliberately a step darker than
                                       // CardWhite now, not near-white, so cards actually
                                       // stand out instead of blending into the page
val CardWhite = Color(0xFFFFFFFF)
val DividerGray = Color(0xFFECECEC) // renamed from `Divider` — collided with Material3's Divider composable

val PillGray = Color(0xFFF1F1F1)      // unselected filter pill background (e.g. "Hot sale")
val StarYellow = Color(0xFFFFC107)

// ===== Dark mode =====
// Sampled from Home_-_Dark.pdf. Forest/ForestLight/Tomato/StarYellow are
// deliberately NOT repeated here — the mockup keeps every accent color
// identical between light and dark, only backgrounds/surfaces/text shift.
// That's also why AfamfreshTheme's DarkColors can keep primary = Forest
// unchanged and the existing status-bar SideEffect (which reads
// colorScheme.primary) needs no changes for dark mode to look right.

val BackgroundDark = Color(0xFF0E1B23)   // page background — deep navy, not pure black
val CardDark = Color(0xFF16262F)         // cards, category circles, search bar, pills
val DividerGrayDark = Color(0xFF283844)  // subtle separators against CardDark/BackgroundDark
val InkDark = Color(0xFFF2F5F6)          // primary text on dark backgrounds
val InkMutedDark = Color(0xFFA7B2B9)     // secondary/muted text on dark backgrounds
