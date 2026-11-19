package com.readicop.lettor.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readicop.lettor.data.local.BookFormat
import com.readicop.lettor.data.local.BookmarkEntity
import com.readicop.lettor.data.local.HighlightEntity
import com.readicop.lettor.data.preferences.OrientationLockMode
import com.readicop.lettor.data.preferences.ReaderFont
import com.readicop.lettor.data.preferences.ReaderPreferences
import com.readicop.lettor.data.preferences.ReaderTheme
import com.readicop.lettor.domain.engine.BookQuestionAnswer
import com.readicop.lettor.domain.engine.EpubChapterStub
import com.readicop.lettor.domain.engine.EpubSearchHit
import com.readicop.lettor.ui.components.LettorRulerBrightnessSlider
import com.readicop.lettor.ui.components.LettorThinSlider
import com.readicop.lettor.ui.theme.JetBrainsMonoFamily
import com.readicop.lettor.ui.theme.LiterataFamily
import com.readicop.lettor.ui.theme.PlayfairDisplayFamily
import com.readicop.lettor.ui.theme.colors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class AppearanceCanvasIcon {
  PAGE,
  LIGHT,
  AUTO_LIGHT,
  ORIENTATION,
  TIME,
  MORE_TUNE,
  CHEVRON_DOWN
}

@Composable
private fun AppearanceCustomIcon(
  type: AppearanceCanvasIcon,
  tint: Color,
  accentTint: Color,
  modifier: Modifier = Modifier
) {
  Canvas(modifier = modifier.size(18.dp)) {
    val w = size.width
    val h = size.height
    val strokePx = 1.65.dp.toPx()
    val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (type) {
      AppearanceCanvasIcon.PAGE -> {
        val pageOutline = Path().apply {
          moveTo(w * 0.22f, h * 0.14f)
          lineTo(w * 0.62f, h * 0.14f)
          lineTo(w * 0.80f, h * 0.32f)
          lineTo(w * 0.80f, h * 0.86f)
          lineTo(w * 0.22f, h * 0.86f)
          close()
        }
        drawPath(pageOutline, color = tint, style = stroke)
        val fold = Path().apply {
          moveTo(w * 0.62f, h * 0.14f)
          lineTo(w * 0.62f, h * 0.32f)
          lineTo(w * 0.80f, h * 0.32f)
        }
        drawPath(fold, color = accentTint, style = stroke)
        drawLine(
          color = accentTint,
          start = Offset(w * 0.35f, h * 0.50f),
          end = Offset(w * 0.66f, h * 0.50f),
          strokeWidth = strokePx,
          cap = StrokeCap.Round
        )
        drawLine(
          color = tint.copy(alpha = 0.72f),
          start = Offset(w * 0.35f, h * 0.67f),
          end = Offset(w * 0.60f, h * 0.67f),
          strokeWidth = strokePx,
          cap = StrokeCap.Round
        )
      }

      AppearanceCanvasIcon.LIGHT -> {
        val center = Offset(w * 0.5f, h * 0.5f)
        drawCircle(
          color = tint,
          radius = w * 0.20f,
          center = center,
          style = stroke
        )
        val innerR = w * 0.31f
        val outerR = w * 0.43f
        for (i in 0 until 8) {
          val rad = (i * 45.0) * (PI / 180.0)
          val cosA = cos(rad).toFloat()
          val sinA = sin(rad).toFloat()
          drawLine(
            color = if (i % 2 == 0) accentTint else tint,
            start = Offset(center.x + innerR * cosA, center.y + innerR * sinA),
            end = Offset(center.x + outerR * cosA, center.y + outerR * sinA),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
        }
      }

      AppearanceCanvasIcon.AUTO_LIGHT -> {
        val center = Offset(w * 0.5f, h * 0.5f)
        drawCircle(color = tint, radius = w * 0.32f, center = center, style = stroke)
        // Diagonal half tone / auto marker
        drawArc(
          color = accentTint,
          startAngle = -90f,
          sweepAngle = 180f,
          useCenter = true,
          topLeft = Offset(w * 0.25f, h * 0.25f),
          size = Size(w * 0.50f, h * 0.50f)
        )
      }

      AppearanceCanvasIcon.ORIENTATION -> {
        drawRoundRect(
          color = tint,
          topLeft = Offset(w * 0.28f, h * 0.16f),
          size = Size(w * 0.44f, h * 0.68f),
          cornerRadius = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx()),
          style = stroke
        )
        val arcPath = Path().apply {
          moveTo(w * 0.14f, h * 0.46f)
          quadraticTo(w * 0.12f, h * 0.72f, w * 0.32f, h * 0.84f)
        }
        drawPath(arcPath, color = accentTint, style = stroke)
        val arrowTip = Path().apply {
          moveTo(w * 0.10f, h * 0.37f)
          lineTo(w * 0.14f, h * 0.48f)
          lineTo(w * 0.24f, h * 0.42f)
        }
        drawPath(arrowTip, color = accentTint, style = stroke)
      }

      AppearanceCanvasIcon.TIME -> {
        val center = Offset(w * 0.5f, h * 0.53f)
        drawCircle(
          color = tint,
          radius = w * 0.34f,
          center = center,
          style = stroke
        )
        drawLine(
          color = accentTint,
          start = Offset(w * 0.39f, h * 0.12f),
          end = Offset(w * 0.61f, h * 0.12f),
          strokeWidth = strokePx,
          cap = StrokeCap.Round
        )
        drawLine(
          color = tint,
          start = center,
          end = Offset(w * 0.50f, h * 0.34f),
          strokeWidth = strokePx,
          cap = StrokeCap.Round
        )
        drawLine(
          color = accentTint,
          start = center,
          end = Offset(w * 0.67f, h * 0.58f),
          strokeWidth = strokePx,
          cap = StrokeCap.Round
        )
      }

      AppearanceCanvasIcon.MORE_TUNE -> {
        val ys = listOf(h * 0.26f, h * 0.50f, h * 0.74f)
        val nodes = listOf(w * 0.64f, w * 0.36f, w * 0.58f)
        ys.forEachIndexed { i, y ->
          drawLine(
            color = tint.copy(alpha = 0.75f),
            start = Offset(w * 0.14f, y),
            end = Offset(w * 0.86f, y),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          drawCircle(
            color = accentTint,
            radius = 2.4.dp.toPx(),
            center = Offset(nodes[i], y)
          )
        }
      }

      AppearanceCanvasIcon.CHEVRON_DOWN -> {
        val path = Path().apply {
          moveTo(w * 0.24f, h * 0.38f)
          lineTo(w * 0.50f, h * 0.64f)
          lineTo(w * 0.76f, h * 0.38f)
        }
        drawPath(path, color = tint, style = stroke)
      }
    }
  }
}

@Composable
private fun AppearanceSectionCard(
  title: String,
  icon: AppearanceCanvasIcon,
  trailingBadge: String? = null,
  cardTag: String,
  content: @Composable () -> Unit
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(18.dp)
      )
      .testTag(cardTag)
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          AppearanceCustomIcon(
            type = icon,
            tint = MaterialTheme.colorScheme.onSurface,
            accentTint = MaterialTheme.colorScheme.primary
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
          )
        }
        if (!trailingBadge.isNullOrBlank()) {
          Text(
            text = trailingBadge,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
      Spacer(Modifier.height(12.dp))
      content()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderAppearanceSheet(
  format: BookFormat,
  preferences: ReaderPreferences,
  sleepTimerRemainingSeconds: Int,
  onSelectTheme: (ReaderTheme) -> Unit,
  onSelectFont: (ReaderFont) -> Unit,
  onFontSizeChange: (Float) -> Unit,
  onLineHeightChange: (Float) -> Unit,
  onPaddingChange: (Int) -> Unit,
  onToggleScrollMode: (Boolean) -> Unit,
  onTogglePdfNightFilter: (Boolean) -> Unit,
  onBrightnessChange: (Float) -> Unit,
  onOrientationLockChange: (OrientationLockMode) -> Unit,
  onSelectSleepTimerMinutes: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  var showMoreSettings by remember { mutableStateOf(false) }
  val chevronRotation by animateFloatAsState(
    targetValue = if (showMoreSettings) 180f else 0f,
    label = "more_settings_chevron"
  )

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text(
        text = "Apariencia y controles",
        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlayfairDisplayFamily)
      )

      // 1. Tarjeta Página (tema, tipografía, tamaño)
      AppearanceSectionCard(
        title = "Página",
        icon = AppearanceCanvasIcon.PAGE,
        trailingBadge = if (format == BookFormat.EPUB) "${preferences.fontSizeSp.roundToInt()} pt" else null,
        cardTag = "appearance_card_page"
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          ReaderTheme.entries.forEach { theme ->
            val palette = theme.colors()
            val selected = preferences.readerTheme == theme
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.background)
                .border(
                  width = if (selected) 2.2.dp else 1.dp,
                  color = if (selected) MaterialTheme.colorScheme.primary else palette.divider,
                  shape = RoundedCornerShape(12.dp)
                )
                .clickable { onSelectTheme(theme) }
                .padding(vertical = 10.dp)
                .testTag("reader_theme_${theme.name.lowercase()}")
            ) {
              Text(
                text = "Aa",
                fontFamily = PlayfairDisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = palette.text
              )
              Spacer(Modifier.height(2.dp))
              Text(
                text = theme.label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondaryText
              )
            }
          }
        }

        if (format == BookFormat.EPUB) {
          Spacer(Modifier.height(14.dp))
          Text(
            text = "TIPOGRAFÍA",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ReaderFont.entries.forEach { font ->
              FilterChip(
                selected = preferences.readerFont == font,
                onClick = { onSelectFont(font) },
                label = { Text(font.label, maxLines = 1) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("reader_font_${font.name.lowercase()}")
              )
            }
          }

          Spacer(Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "TAMAÑO DE LETRA",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = "${preferences.fontSizeSp.roundToInt()} pt",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.primary
            )
          }
          LettorThinSlider(
            value = preferences.fontSizeSp,
            onValueChange = onFontSizeChange,
            valueRange = 14f..28f,
            steps = 13,
            modifier = Modifier.testTag("reader_font_size_slider")
          )
        }
      }

      // 2. Tarjeta Luz (brillo con regla + orientación)
      AppearanceSectionCard(
        title = "Luz",
        icon = AppearanceCanvasIcon.LIGHT,
        trailingBadge = if (preferences.readerBrightness < 0f) "Auto" else "${(preferences.readerBrightness * 100f).roundToInt()}%",
        cardTag = "appearance_card_light"
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          FilterChip(
            selected = preferences.readerBrightness < 0f,
            onClick = { onBrightnessChange(-1f) },
            label = { Text("Auto") },
            leadingIcon = {
              AppearanceCustomIcon(
                type = AppearanceCanvasIcon.AUTO_LIGHT,
                tint = if (preferences.readerBrightness < 0f) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                accentTint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
              )
            },
            modifier = Modifier.testTag("reader_brightness_auto")
          )
          LettorRulerBrightnessSlider(
            value = if (preferences.readerBrightness < 0f) 0.5f else preferences.readerBrightness,
            onValueChange = { onBrightnessChange(it.coerceIn(0.05f, 1.0f)) },
            valueRange = 0.05f..1.0f,
            isAuto = preferences.readerBrightness < 0f,
            modifier = Modifier
              .weight(1f)
              .testTag("reader_brightness_slider")
          )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          AppearanceCustomIcon(
            type = AppearanceCanvasIcon.ORIENTATION,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            accentTint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = "ORIENTACIÓN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OrientationLockMode.entries.forEach { mode ->
            FilterChip(
              selected = preferences.orientationLock == mode,
              onClick = { onOrientationLockChange(mode) },
              label = { Text(mode.label, maxLines = 1) },
              modifier = Modifier
                .weight(1f)
                .testTag("reader_orientation_${mode.name.lowercase()}")
            )
          }
        }
      }

      // 3. Tarjeta Tiempo (temporizador)
      val activeTimerBadge = if (sleepTimerRemainingSeconds > 0) {
        val mins = sleepTimerRemainingSeconds / 60
        val secs = sleepTimerRemainingSeconds % 60
        String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
      } else {
        "Apagado"
      }
      AppearanceSectionCard(
        title = "Tiempo",
        icon = AppearanceCanvasIcon.TIME,
        trailingBadge = activeTimerBadge,
        cardTag = "appearance_card_time"
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          listOf(0 to "Apagado", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "60 min").forEach { (mins, label) ->
            val active = (mins == 0 && sleepTimerRemainingSeconds == 0) ||
              (mins > 0 && sleepTimerRemainingSeconds in ((mins - 1) * 60 + 1)..(mins * 60))
            FilterChip(
              selected = active,
              onClick = { onSelectSleepTimerMinutes(mins) },
              label = { Text(label) },
              modifier = Modifier.testTag("sleep_timer_$mins")
            )
          }
        }
      }

      // Bloque colapsable: Más ajustes (Interlineado, Márgenes y modos de lectura)
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(18.dp)
          )
      ) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { showMoreSettings = !showMoreSettings }
              .padding(horizontal = 16.dp, vertical = 14.dp)
              .testTag("appearance_more_settings_toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              AppearanceCustomIcon(
                type = AppearanceCanvasIcon.MORE_TUNE,
                tint = MaterialTheme.colorScheme.onSurface,
                accentTint = MaterialTheme.colorScheme.primary
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = "Más ajustes",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
              )
            }
            AppearanceCustomIcon(
              type = AppearanceCanvasIcon.CHEVRON_DOWN,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              accentTint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.rotate(chevronRotation)
            )
          }

          AnimatedVisibility(
            visible = showMoreSettings,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 14.dp)
                .testTag("appearance_more_settings_content")
            ) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
              Spacer(Modifier.height(12.dp))

              if (format == BookFormat.EPUB) {
                Text(
                  text = "INTERLINEADO",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  listOf(
                    "Compacto" to 1.35f,
                    "Cómodo" to 1.55f,
                    "Amplio" to 1.8f
                  ).forEach { (label, mult) ->
                    val selected = kotlin.math.abs(preferences.lineHeightMultiplier - mult) < 0.08f
                    FilterChip(
                      selected = selected,
                      onClick = { onLineHeightChange(mult) },
                      label = { Text(label) },
                      modifier = Modifier.weight(1f)
                    )
                  }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                  text = "MÁRGENES",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  listOf(
                    "Estrecho" to 14,
                    "Normal" to 22,
                    "Amplio" to 32
                  ).forEach { (label, dpVal) ->
                    val selected = kotlin.math.abs(preferences.horizontalPaddingDp - dpVal) <= 3
                    FilterChip(
                      selected = selected,
                      onClick = { onPaddingChange(dpVal) },
                      label = { Text(label) },
                      modifier = Modifier.weight(1f)
                    )
                  }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleScrollMode(!preferences.epubScrollMode) }
                    .padding(top = 10.dp, bottom = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Desplazamiento vertical continuo", style = MaterialTheme.typography.titleSmall)
                    Text(
                      "Cambia entre pasar páginas horizontales o hacer scroll",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Switch(
                    checked = preferences.epubScrollMode,
                    onCheckedChange = onToggleScrollMode,
                    modifier = Modifier.testTag("reader_epub_scroll_switch")
                  )
                }
              } else {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleScrollMode(!preferences.pdfScrollMode) }
                    .padding(vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Desplazamiento vertical continuo", style = MaterialTheme.typography.titleSmall)
                    Text(
                      "Desliza las páginas hacia abajo en lugar de pasar lateralmente",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Switch(
                    checked = preferences.pdfScrollMode,
                    onCheckedChange = onToggleScrollMode,
                    modifier = Modifier.testTag("reader_pdf_scroll_switch")
                  )
                }

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTogglePdfNightFilter(!preferences.pdfNightFilter) }
                    .padding(vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Filtro nocturno en PDF", style = MaterialTheme.typography.titleSmall)
                    Text(
                      "Invierte el brillo del PDF cuando lees en Noche o Ébano",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Switch(
                    checked = preferences.pdfNightFilter,
                    onCheckedChange = onTogglePdfNightFilter,
                    modifier = Modifier.testTag("reader_pdf_night_switch")
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocAndBookmarksSheet(
  format: BookFormat,
  chapterStubs: List<EpubChapterStub>,
  pdfPageCount: Int,
  currentUnit: Int,
  bookmarks: List<BookmarkEntity>,
  highlights: List<HighlightEntity>,
  onJumpToUnit: (Int) -> Unit,
  onDeleteBookmark: (Long) -> Unit,
  onDeleteHighlight: (Long) -> Unit,
  onDismiss: () -> Unit
) {
  var selectedTab by remember { mutableIntStateOf(0) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 340.dp, max = 560.dp)
    ) {
      TabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = {
            Text(
              if (format == BookFormat.EPUB) "Índice (${chapterStubs.size})"
              else "Páginas ($pdfPageCount)"
            )
          },
          modifier = Modifier.testTag("toc_tab_index")
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Marcadores (${bookmarks.size})") },
          modifier = Modifier.testTag("toc_tab_bookmarks")
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = { Text("Notas (${highlights.size})") },
          modifier = Modifier.testTag("toc_tab_highlights")
        )
      }

      when (selectedTab) {
        0 -> {
          if (format == BookFormat.EPUB) {
            LazyColumn(
              contentPadding = PaddingValues(vertical = 8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(chapterStubs, key = { it.index }) { chapter ->
                val isCurrent = chapter.index == currentUnit
                ListItem(
                  headlineContent = {
                    Text(
                      text = chapter.title,
                      fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                      color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis
                    )
                  },
                  leadingContent = {
                    Text(
                      text = "${chapter.index + 1}",
                      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                      color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  },
                  modifier = Modifier
                    .clickable {
                      onJumpToUnit(chapter.index)
                      onDismiss()
                    }
                    .testTag("toc_chapter_${chapter.index}")
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
              }
            }
          } else {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 68.dp),
              contentPadding = PaddingValues(20.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(pdfPageCount) { pageIdx ->
                val isCurrent = pageIdx == currentUnit
                Surface(
                  onClick = {
                    onJumpToUnit(pageIdx)
                    onDismiss()
                  },
                  shape = RoundedCornerShape(10.dp),
                  color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                  Text(
                    text = "${pageIdx + 1}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp)
                  )
                }
              }
            }
          }
        }

        1 -> {
          if (bookmarks.isEmpty()) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text("No hay marcadores en este libro", style = MaterialTheme.typography.titleSmall)
              Spacer(Modifier.height(4.dp))
              Text(
                "Usa el icono de marcador en la barra superior para guardar tu posición.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          } else {
            LazyColumn(
              contentPadding = PaddingValues(vertical = 8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(bookmarks, key = { it.id }) { bm ->
                ListItem(
                  headlineContent = { Text(bm.label, fontWeight = FontWeight.SemiBold) },
                  supportingContent = {
                    Column {
                      if (bm.excerpt.isNotBlank()) {
                        Text(
                          text = "«${bm.excerpt}»",
                          style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                          maxLines = 2,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                      if (bm.note.isNotBlank()) {
                        Text(text = bm.note, style = MaterialTheme.typography.bodySmall)
                      }
                    }
                  },
                  leadingContent = {
                    Icon(
                      imageVector = Icons.Filled.Bookmark,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  trailingContent = {
                    IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                      Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar marcador")
                    }
                  },
                  modifier = Modifier.clickable {
                    onJumpToUnit(bm.unitIndex)
                    onDismiss()
                  }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
              }
            }
          }
        }

        else -> {
          if (highlights.isEmpty()) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text("Sin resaltados ni notas adhesivas", style = MaterialTheme.typography.titleSmall)
              Spacer(Modifier.height(4.dp))
              Text(
                "Mantén pulsado un párrafo (EPUB) o una página (PDF) para resaltar o dejar una nota adhesiva.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          } else {
            LazyColumn(
              contentPadding = PaddingValues(vertical = 8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(highlights, key = { it.id }) { hl ->
                ListItem(
                  headlineContent = {
                    Text(
                      text = if (format == BookFormat.EPUB) "Capítulo ${hl.unitIndex + 1}" else "Página ${hl.unitIndex + 1}",
                      fontWeight = FontWeight.SemiBold
                    )
                  },
                  supportingContent = {
                    Column {
                      if (hl.excerpt.isNotBlank()) {
                        Text(
                          text = "«${hl.excerpt}»",
                          style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                          maxLines = 2,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                      if (hl.stickyNote.isNotBlank()) {
                        Text(
                          text = "Nota: ${hl.stickyNote}",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.primary
                        )
                      }
                    }
                  },
                  leadingContent = {
                    Box(
                      modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(hl.color.dotColor)
                    )
                  },
                  trailingContent = {
                    IconButton(onClick = { onDeleteHighlight(hl.id) }) {
                      Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar resaltado")
                    }
                  },
                  modifier = Modifier.clickable {
                    onJumpToUnit(hl.unitIndex)
                    onDismiss()
                  }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSearchSheet(
  initialTab: Int = 0,
  query: String,
  results: List<EpubSearchHit>,
  askQuestion: String,
  bookAnswer: BookQuestionAnswer?,
  onQueryChange: (String) -> Unit,
  onAskQuestion: (String) -> Unit,
  onSelectChapter: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
  var questionInput by remember(askQuestion) { mutableStateOf(askQuestion) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 360.dp, max = 560.dp)
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp)
    ) {
      TabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Buscar en el libro") },
          modifier = Modifier.testTag("search_tab_exact")
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Preguntar al libro") },
          modifier = Modifier.testTag("search_tab_ask")
        )
      }

      Spacer(Modifier.height(14.dp))

      if (selectedTab == 0) {
        OutlinedTextField(
          value = query,
          onValueChange = onQueryChange,
          placeholder = { Text("Escribe una palabra o frase…") },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Close, contentDescription = "Limpiar")
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("reader_search_input")
        )
        Spacer(Modifier.height(12.dp))

        if (query.isBlank()) {
          Text(
            text = "Busca términos dentro de todos los capítulos de este EPUB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else if (results.isEmpty()) {
          Text(
            text = "Sin resultados para «$query».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results) { hit ->
              Surface(
                onClick = {
                  onSelectChapter(hit.chapterIndex)
                  onDismiss()
                },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Text(
                    text = hit.chapterTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            }
          }
        }
      } else {
        // Preguntar al libro — local BM25 extractive search
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = questionInput,
            onValueChange = { questionInput = it },
            placeholder = { Text("Ej. ¿Qué ocurre con los molinos?") },
            leadingIcon = { Icon(Icons.Default.QuestionAnswer, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("ask_book_input")
          )
          Button(
            onClick = { onAskQuestion(questionInput) },
            enabled = questionInput.isNotBlank(),
            modifier = Modifier.testTag("ask_book_submit")
          ) {
            Text("Consultar")
          }
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          listOf("molinos", "caballero", "aventura", "historia").forEach { suggestion ->
            AssistChip(
              onClick = {
                questionInput = suggestion
                onAskQuestion(suggestion)
              },
              label = { Text(suggestion) }
            )
          }
        }

        Spacer(Modifier.height(10.dp))

        if (bookAnswer == null) {
          Text(
            text = "Consulta cualquier personaje, escena o dato mediante búsqueda semántica local (100% sin servidores).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  text = bookAnswer.summaryAnswer,
                  style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LiterataFamily),
                  modifier = Modifier.padding(14.dp)
                )
              }
            }
            items(bookAnswer.passages) { passage ->
              Surface(
                onClick = {
                  onSelectChapter(passage.chapterIndex)
                  onDismiss()
                },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Text(
                    text = passage.chapterTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = "«${passage.passage}»",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun AddBookmarkNoteDialog(
  defaultLabel: String,
  onSave: (note: String) -> Unit,
  onDismiss: () -> Unit
) {
  var noteText by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Guardar marcador y nota") },
    text = {
      Column {
        Text(
          text = defaultLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = noteText,
          onValueChange = { noteText = it },
          label = { Text("Nota personal (opcional)") },
          placeholder = { Text("Escribe una reflexión o cita…") },
          minLines = 2,
          maxLines = 4,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("bookmark_note_input")
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(noteText)
          onDismiss()
        },
        modifier = Modifier.testTag("save_bookmark_note_button")
      ) {
        Text("Guardar")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancelar")
      }
    }
  )
}
