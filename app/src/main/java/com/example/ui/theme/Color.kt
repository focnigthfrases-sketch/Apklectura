package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.preferences.ReaderTheme

// Editorial Literary Atelier Palette — Light
val PaperBackground = Color(0xFFFAF7F2)
val PaperSurface = Color(0xFFF3EDE3)
val PaperSurfaceElevated = Color(0xFFFFFFFF)
val PaperSurfaceVariant = Color(0xFFEAE1D3)
val InkPrimary = Color(0xFF1D1A16)
val InkSecondary = Color(0xFF5D564E)
val InkOutline = Color(0xFFD6CCBE)
val TerracottaPrimary = Color(0xFFB5482D)
val TerracottaContainer = Color(0xFFF6DFD8)
val OnTerracottaContainer = Color(0xFF5A1D0E)
val SageSecondary = Color(0xFF556851)
val SageContainer = Color(0xFFDCE5D8)
val OnSageContainer = Color(0xFF1D291B)
val OchreTertiary = Color(0xFF9C6B2F)
val OchreContainer = Color(0xFFF3E4CE)

// Editorial Literary Atelier Palette — Dark
val EspressoBackground = Color(0xFF141210)
val EspressoSurface = Color(0xFF1E1B18)
val EspressoSurfaceElevated = Color(0xFF28241F)
val EspressoSurfaceVariant = Color(0xFF322C26)
val AlabasterPrimary = Color(0xFFEDE6DA)
val AlabasterSecondary = Color(0xFFB5ACA0)
val EspressoOutline = Color(0xFF423B33)
val TerracottaDark = Color(0xFFE57F64)
val TerracottaContainerDark = Color(0xFF5E2516)
val OnTerracottaContainerDark = Color(0xFFFFDBD1)
val SageDark = Color(0xFF9DB597)
val SageContainerDark = Color(0xFF2D3A2A)
val OnSageContainerDark = Color(0xFFDCE5D8)
val OchreDark = Color(0xFFD7A668)

// Dedicated Reader Surfaces
data class ReaderSurfaceColors(
  val background: Color,
  val surface: Color,
  val text: Color,
  val secondaryText: Color,
  val accent: Color,
  val divider: Color,
  val isDark: Boolean
)

fun ReaderTheme.colors(): ReaderSurfaceColors = when (this) {
  ReaderTheme.PAPER -> ReaderSurfaceColors(
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFF1EAE0),
    text = Color(0xFF1E1B18),
    secondaryText = Color(0xFF6B6359),
    accent = Color(0xFFB5482D),
    divider = Color(0xFFE0D7CA),
    isDark = false
  )
  ReaderTheme.SEPIA -> ReaderSurfaceColors(
    background = Color(0xFFF2E6CE),
    surface = Color(0xFFE7D7B7),
    text = Color(0xFF392B1D),
    secondaryText = Color(0xFF705943),
    accent = Color(0xFF9A3E25),
    divider = Color(0xFFD6C29D),
    isDark = false
  )
  ReaderTheme.NIGHT -> ReaderSurfaceColors(
    background = Color(0xFF181A1F),
    surface = Color(0xFF23262E),
    text = Color(0xFFD7DCE3),
    secondaryText = Color(0xFF8C94A3),
    accent = Color(0xFFE57F64),
    divider = Color(0xFF323743),
    isDark = true
  )
  ReaderTheme.EBONY -> ReaderSurfaceColors(
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    text = Color(0xFFC9C4BC),
    secondaryText = Color(0xFF7C7770),
    accent = Color(0xFFE57F64),
    divider = Color(0xFF262626),
    isDark = true
  )
}
