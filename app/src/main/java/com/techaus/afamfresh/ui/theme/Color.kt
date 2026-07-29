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

val Cream = Color(0xFFFAFAF7)         // app background
val CardWhite = Color(0xFFFFFFFF)
val DividerGray = Color(0xFFECECEC) // renamed from `Divider` — collided with Material3's Divider composable

val PillGray = Color(0xFFF1F1F1)      // unselected filter pill background (e.g. "Hot sale")
val StarYellow = Color(0xFFFFC107)
