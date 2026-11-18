package com.readicop.lettor.ui.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readicop.lettor.data.preferences.AppThemeMode
import com.readicop.lettor.data.preferences.ReaderFont
import com.readicop.lettor.data.preferences.ReaderTheme
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.readicop.lettor.notifications.ToucanArtwork
import com.readicop.lettor.monetization.AdMobManager
import com.readicop.lettor.ui.theme.PlayfairDisplayFamily
import com.readicop.lettor.ui.theme.colors
import com.readicop.lettor.ui.theme.fontFamily
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
  viewModel: SettingsViewModel,
  adMobManager: AdMobManager,
  modifier: Modifier = Modifier
) {
  val prefs by viewModel.preferences.collectAsStateWithLifecycle()
  val isBackupBusy by viewModel.isBackupBusy.collectAsStateWithLifecycle()
  val readerColors = prefs.readerTheme.colors()

  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val activity = context as? Activity

  LaunchedEffect(viewModel) {
    viewModel.messages.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  val toucanBadge = remember(context) { ToucanArtwork.getOrCreateBitmap(context).asImageBitmap() }
  var pendingPermissionPurpose by remember { mutableStateOf<String?>(null) }
  var showPermissionRationaleDialog by remember { mutableStateOf(false) }

  // Compliant Android 13+ runtime permission for notifications (always preceded by explanation dialog)
  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    val purpose = pendingPermissionPurpose
    pendingPermissionPurpose = null
    if (granted) {
      when (purpose) {
        "resume" -> viewModel.setResumeReminderEnabled(true)
        else -> viewModel.setReminder(true, prefs.reminderHour, prefs.reminderMinute)
      }
    } else {
      scope.launch {
        snackbarHostState.showSnackbar("Permiso de notificaciones denegado. Puedes activarlo en Ajustes del sistema.")
      }
    }
  }

  val requestNotificationWithExplanation: (String, () -> Unit) -> Unit = { purpose, onAlreadyGranted ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      pendingPermissionPurpose = purpose
      showPermissionRationaleDialog = true
    } else {
      onAlreadyGranted()
    }
  }

  val exportBackupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/zip")
  ) { uri ->
    if (uri != null) viewModel.exportBackup(uri)
  }

  val importBackupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) viewModel.importBackup(uri)
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 640.dp)
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 14.dp)
      ) {
        Text(
          text = "Ajustes",
          style = MaterialTheme.typography.displaySmall.copy(fontFamily = PlayfairDisplayFamily)
        )
        Text(
          text = "Personaliza tu estudio de lectura Lettor",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // App Theme
        SectionLabel("APARIENCIA DE LA APLICACIÓN")
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          AppThemeMode.entries.forEachIndexed { idx, mode ->
            SegmentedButton(
              selected = prefs.appThemeMode == mode,
              onClick = { viewModel.setAppThemeMode(mode) },
              shape = SegmentedButtonDefaults.itemShape(idx, AppThemeMode.entries.size),
              modifier = Modifier.testTag("app_theme_${mode.name.lowercase()}")
            ) {
              Text(mode.label, maxLines = 1)
            }
          }
        }

        Spacer(Modifier.height(24.dp))

        // Live Reader Preview
        SectionLabel("VISTA PREVIA DEL LECTOR EPUB")
        Spacer(Modifier.height(10.dp))
        Surface(
          color = readerColors.background,
          shape = RoundedCornerShape(18.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, readerColors.divider, RoundedCornerShape(18.dp))
        ) {
          Column(modifier = Modifier.padding(20.dp)) {
            Text(
              text = "«En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo…»",
              fontFamily = prefs.readerFont.fontFamily(),
              fontSize = prefs.fontSizeSp.sp,
              lineHeight = (prefs.fontSizeSp * prefs.lineHeightMultiplier).sp,
              color = readerColors.text
            )
          }
        }

        Spacer(Modifier.height(18.dp))

        SectionLabel("PAPEL DE LECTURA PREDETERMINADO")
        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          ReaderTheme.entries.forEach { theme ->
            val palette = theme.colors()
            val selected = prefs.readerTheme == theme
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.background)
                .border(
                  width = if (selected) 2.5.dp else 1.dp,
                  color = if (selected) MaterialTheme.colorScheme.primary else palette.divider,
                  shape = RoundedCornerShape(14.dp)
                )
                .clickable { viewModel.setReaderTheme(theme) }
                .padding(vertical = 14.dp, horizontal = 6.dp)
                .testTag("settings_reader_theme_${theme.name.lowercase()}")
            ) {
              Text(
                text = "Aa",
                fontFamily = PlayfairDisplayFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = palette.text
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = theme.label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondaryText
              )
            }
          }
        }

        Spacer(Modifier.height(22.dp))

        SectionLabel("TIPOGRAFÍA")
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          ReaderFont.entries.forEach { font ->
            FilterChip(
              selected = prefs.readerFont == font,
              onClick = { viewModel.setReaderFont(font) },
              label = { Text(font.label, maxLines = 1) },
              modifier = Modifier
                .weight(1f)
                .testTag("settings_font_${font.name.lowercase()}")
            )
          }
        }

        Spacer(Modifier.height(18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          SectionLabel("TAMAÑO DE TEXTO")
          Text(
            text = "${prefs.fontSizeSp.roundToInt()} pt",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
          )
        }
        Slider(
          value = prefs.fontSizeSp,
          onValueChange = { viewModel.setFontSizeSp(it) },
          valueRange = 14f..28f,
          steps = 13,
          modifier = Modifier.testTag("settings_font_size_slider")
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))

        // Daily Goal & Reminder Notifications
        SectionLabel("OBJETIVO DIARIO Y RECORDATORIOS")
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf(10, 20, 30, 45).forEach { mins ->
            FilterChip(
              selected = prefs.dailyGoalMinutes == mins,
              onClick = { viewModel.setDailyGoalMinutes(mins) },
              label = { Text("$mins min") },
              modifier = Modifier.weight(1f)
            )
          }
        }

        Spacer(Modifier.height(10.dp))
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Image(
                bitmap = toucanBadge,
                contentDescription = "Mascota tucán de Lettor",
                modifier = Modifier.size(46.dp)
              )
              Spacer(Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Aviso «Te quedaste leyendo»",
                  style = MaterialTheme.typography.titleSmall
                )
                Text(
                  text = "Te recuerda el libro que dejaste a medias con su capítulo y progreso real.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              Switch(
                checked = prefs.resumeReminderEnabled,
                onCheckedChange = { enable ->
                  if (!enable) {
                    viewModel.setResumeReminderEnabled(false)
                  } else {
                    requestNotificationWithExplanation("resume") {
                      viewModel.setResumeReminderEnabled(true)
                    }
                  }
                },
                modifier = Modifier.testTag("toggle_resume_reading_notification")
              )
            }

            if (prefs.resumeReminderEnabled) {
              Spacer(Modifier.height(10.dp))
              Text(
                text = "AVISAR TRAS SALIR DEL LECTOR · MÁX. 1 POR LIBRO (09:00–22:00)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
              Spacer(Modifier.height(6.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                listOf(24, 36, 48).forEach { hours ->
                  FilterChip(
                    selected = prefs.resumeReminderDelayHours == hours,
                    onClick = { viewModel.setResumeReminderDelayHours(hours) },
                    label = { Text("$hours h") },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("resume_delay_$hours")
                  )
                }
              }
            }
          }
        }

        Spacer(Modifier.height(8.dp))
        ToggleSettingRow(
          title = "Recordatorio diario de lectura",
          subtitle = "Notificación diaria para cumplir tu meta",
          checked = prefs.reminderEnabled,
          onCheckedChange = { enable ->
            if (!enable) {
              viewModel.setReminder(false, prefs.reminderHour, prefs.reminderMinute)
            } else {
              requestNotificationWithExplanation("daily") {
                viewModel.setReminder(true, prefs.reminderHour, prefs.reminderMinute)
              }
            }
          },
          tag = "toggle_reading_reminder"
        )

        if (prefs.reminderEnabled) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Default.NotificationsActive,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp)
            )
            listOf(9 to 0, 14 to 0, 20 to 30, 22 to 0).forEach { (h, m) ->
              val selected = prefs.reminderHour == h && prefs.reminderMinute == m
              FilterChip(
                selected = selected,
                onClick = { viewModel.setReminder(true, h, m) },
                label = { Text("${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}") },
                modifier = Modifier.weight(1f)
              )
            }
          }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))

        SectionLabel("COMPORTAMIENTO DE LECTURA")
        Spacer(Modifier.height(8.dp))

        ToggleSettingRow(
          title = "Desplazamiento vertical en EPUB",
          subtitle = "Lee capítulos en scroll continuo en vez de pasar páginas",
          checked = prefs.epubScrollMode,
          onCheckedChange = viewModel::setEpubScrollMode,
          tag = "toggle_epub_scroll"
        )

        ToggleSettingRow(
          title = "Desplazamiento vertical en PDF",
          subtitle = "Desliza páginas verticalmente de forma continua",
          checked = prefs.pdfScrollMode,
          onCheckedChange = viewModel::setPdfScrollMode,
          tag = "toggle_pdf_scroll"
        )

        ToggleSettingRow(
          title = "Filtro nocturno en PDF",
          subtitle = "Invierte y suaviza páginas PDF en temas Noche y Ébano",
          checked = prefs.pdfNightFilter,
          onCheckedChange = viewModel::setPdfNightFilter,
          tag = "toggle_pdf_night_filter"
        )

        ToggleSettingRow(
          title = "Mantener pantalla encendida",
          subtitle = "Evita que la pantalla se apague mientras lees",
          checked = prefs.keepScreenOn,
          onCheckedChange = viewModel::setKeepScreenOn,
          tag = "toggle_keep_screen_on"
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))

        // Backup Export / Import
        SectionLabel("COPIAS DE SEGURIDAD LOCALES")
        Spacer(Modifier.height(8.dp))
        Text(
          text = "Guarda o restaura tus libros, portadas, resaltados, notas adhesivas y vocabulario en un archivo ZIP privado.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = { exportBackupLauncher.launch("lettor_copia_seguridad.zip") },
            enabled = !isBackupBusy,
            modifier = Modifier.weight(1f).testTag("export_backup_button")
          ) {
            if (isBackupBusy) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("Exportar ZIP")
          }
          OutlinedButton(
            onClick = { importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            enabled = !isBackupBusy,
            modifier = Modifier.weight(1f).testTag("import_backup_button")
          ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Restaurar ZIP")
          }
        }

        Spacer(Modifier.height(20.dp))

        // Optional Rewarded Ad Card (Additional Tools / Support)
        SectionLabel("HERRAMIENTAS ADICIONALES Y APOYO")
        Spacer(Modifier.height(8.dp))
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Default.VolunteerActivism,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
              )
              Spacer(Modifier.width(10.dp))
              Text(
                text = if (prefs.extraToolsUnlockedToday) {
                  "Mecenas del día activado · ¡Gracias!"
                } else {
                  "Apoya a Lettor (Opcional)"
                },
                style = MaterialTheme.typography.titleSmall
              )
            }
            Text(
              text = "Anuncios de importación hoy: ${prefs.dailyImportInterstitialCount} / 3 máximo al día. Nunca verás anuncios mientras lees.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
              onClick = {
                adMobManager.showOptionalRewardedAd(
                  activity = activity,
                  onRewardGranted = {
                    scope.launch {
                      snackbarHostState.showSnackbar("¡Gracias por apoyar a Lettor! Insignia de Mecenas activada hoy.")
                    }
                  },
                  onUnavailableOrClosed = { rewarded ->
                    if (!rewarded) {
                      scope.launch {
                        snackbarHostState.showSnackbar("Anuncio opcional no disponible en este momento. ¡Sigue leyendo sin interrupciones!")
                      }
                    }
                  }
                )
              },
              modifier = Modifier.testTag("optional_rewarded_ad_button")
            ) {
              Text(
                if (prefs.extraToolsUnlockedToday) "Ver anuncio opcional nuevamente"
                else "Ver anuncio recompensado opcional"
              )
            }
          }
        }

        Spacer(Modifier.height(24.dp))
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "Lettor · Edición Editorial",
              style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlayfairDisplayFamily)
            )
            Spacer(Modifier.height(4.dp))
            Text(
              text = "Lector nativo 100% offline para libros EPUB y PDF con resumen local, diccionario, TTS, resaltado multicolor, copias ZIP y widget de inicio.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
        Spacer(Modifier.height(32.dp))
      }
    }
  }

  if (showPermissionRationaleDialog) {
    AlertDialog(
      onDismissRequest = {
        showPermissionRationaleDialog = false
        pendingPermissionPurpose = null
      },
      icon = {
        Image(
          bitmap = toucanBadge,
          contentDescription = null,
          modifier = Modifier.size(56.dp)
        )
      },
      title = { Text("Avisos de lectura respetuosos") },
      text = {
        Text(
          "Lettor necesita permiso para enviarte notificaciones locales cuando dejas un libro a medias o para recordarte tu meta diaria.\n\n" +
            "• Máximo 1 aviso por libro y periodo.\n" +
            "• Nunca te molestamos de madrugada (22:00–09:00).\n" +
            "• Puedes desactivarlo en cualquier momento."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showPermissionRationaleDialog = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
          },
          modifier = Modifier.testTag("confirm_notification_rationale_button")
        ) {
          Text("Permitir avisos")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showPermissionRationaleDialog = false
            pendingPermissionPurpose = null
          }
        ) {
          Text("Ahora no")
        }
      }
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary
  )
}

@Composable
private fun ToggleSettingRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  tag: String
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(tag)
    )
  }
}
