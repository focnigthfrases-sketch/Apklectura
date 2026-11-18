package com.readicop.lettor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.readicop.lettor.data.preferences.AppThemeMode

private val LettorLightColorScheme = lightColorScheme(
  primary = TerracottaPrimary,
  onPrimary = PaperSurfaceElevated,
  primaryContainer = TerracottaContainer,
  onPrimaryContainer = OnTerracottaContainer,
  secondary = SageSecondary,
  onSecondary = PaperSurfaceElevated,
  secondaryContainer = SageContainer,
  onSecondaryContainer = OnSageContainer,
  tertiary = OchreTertiary,
  onTertiary = PaperSurfaceElevated,
  tertiaryContainer = OchreContainer,
  onTertiaryContainer = InkPrimary,
  background = PaperBackground,
  onBackground = InkPrimary,
  surface = PaperBackground,
  onSurface = InkPrimary,
  surfaceVariant = PaperSurface,
  onSurfaceVariant = InkSecondary,
  surfaceContainerLowest = PaperSurfaceElevated,
  surfaceContainerLow = PaperSurface,
  surfaceContainer = PaperSurface,
  surfaceContainerHigh = PaperSurfaceVariant,
  outline = InkOutline,
  outlineVariant = InkOutline.copy(alpha = 0.55f)
)

private val LettorDarkColorScheme = darkColorScheme(
  primary = TerracottaDark,
  onPrimary = EspressoBackground,
  primaryContainer = TerracottaContainerDark,
  onPrimaryContainer = OnTerracottaContainerDark,
  secondary = SageDark,
  onSecondary = EspressoBackground,
  secondaryContainer = SageContainerDark,
  onSecondaryContainer = OnSageContainerDark,
  tertiary = OchreDark,
  onTertiary = EspressoBackground,
  background = EspressoBackground,
  onBackground = AlabasterPrimary,
  surface = EspressoBackground,
  onSurface = AlabasterPrimary,
  surfaceVariant = EspressoSurface,
  onSurfaceVariant = AlabasterSecondary,
  surfaceContainerLowest = EspressoBackground,
  surfaceContainerLow = EspressoSurface,
  surfaceContainer = EspressoSurface,
  surfaceContainerHigh = EspressoSurfaceElevated,
  surfaceContainerHighest = EspressoSurfaceVariant,
  outline = EspressoOutline,
  outlineVariant = EspressoOutline.copy(alpha = 0.55f)
)

val LettorShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(14.dp),
  large = RoundedCornerShape(20.dp),
  extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LettorTheme(
  appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
  content: @Composable () -> Unit
) {
  val useDarkTheme = when (appThemeMode) {
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
  }

  val colorScheme = if (useDarkTheme) LettorDarkColorScheme else LettorLightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = LettorTypography,
    shapes = LettorShapes,
    content = content
  )
}
