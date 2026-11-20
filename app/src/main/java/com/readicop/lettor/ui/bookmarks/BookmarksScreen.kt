package com.readicop.lettor.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readicop.lettor.data.local.BookmarkWithBook
import com.readicop.lettor.data.local.HighlightWithBook
import com.readicop.lettor.data.local.VocabularyEntity
import com.readicop.lettor.ui.components.FormatTagPill
import com.readicop.lettor.ui.components.LettorImeBackDialogHandler
import com.readicop.lettor.ui.theme.JetBrainsMonoFamily
import com.readicop.lettor.ui.theme.LiterataFamily
import com.readicop.lettor.ui.theme.PlayfairDisplayFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BookmarksScreen(
  viewModel: BookmarksViewModel,
  onOpenBookmark: (bookId: Long, unitIndex: Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  var selectedTab by remember { mutableIntStateOf(0) }
  var showAddWordDialog by remember { mutableStateOf(false) }

  Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Estudio y Notas",
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = PlayfairDisplayFamily)
          )
          Text(
            text = "Resaltados, notas adhesivas, vocabulario y progreso",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        if (selectedTab == 1) {
          IconButton(
            onClick = { showAddWordDialog = true },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("add_vocab_word_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = "Añadir palabra")
          }
        }
      }

      TabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Notas (${uiState.stats.totalAnnotations})") },
          modifier = Modifier.testTag("study_tab_notes")
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Vocabulario (${uiState.stats.vocabularyCount})") },
          modifier = Modifier.testTag("study_tab_vocab")
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = { Text("Estadísticas") },
          modifier = Modifier.testTag("study_tab_stats")
        )
      }

      when (selectedTab) {
        0 -> {
          if (uiState.bookmarks.isEmpty() && uiState.highlights.isEmpty()) {
            EmptyStudyTabState(
              icon = Icons.Outlined.Bookmarks,
              title = "Sin notas ni resaltados aún",
              subtitle = "Mantén pulsado cualquier párrafo de un EPUB o añade una nota adhesiva desde el lector."
            )
          } else {
            LazyColumn(
              contentPadding = PaddingValues(20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              items(uiState.highlights, key = { "hl_${it.highlight.id}" }) { item ->
                HighlightCard(
                  item = item,
                  onClick = { onOpenBookmark(item.highlight.bookId, item.highlight.unitIndex) },
                  onDelete = { viewModel.deleteHighlight(item.highlight.id) }
                )
              }
              items(uiState.bookmarks, key = { "bm_${it.bookmark.id}" }) { item ->
                BookmarkCard(
                  item = item,
                  onClick = { onOpenBookmark(item.bookmark.bookId, item.bookmark.unitIndex) },
                  onDelete = { viewModel.deleteBookmark(item.bookmark.id) }
                )
              }
            }
          }
        }

        1 -> {
          if (uiState.vocabulary.isEmpty()) {
            EmptyStudyTabState(
              icon = Icons.Outlined.School,
              title = "Tu glosario personal está vacío",
              subtitle = "Consulta palabras en el diccionario integrado mientras lees y guárdalas aquí para repasarlas."
            )
          } else {
            LazyColumn(
              contentPadding = PaddingValues(20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              items(uiState.vocabulary, key = { it.id }) { word ->
                VocabularyCard(
                  entry = word,
                  onToggleMastered = { viewModel.toggleVocabularyMastered(word) },
                  onDelete = { viewModel.deleteVocabularyWord(word.id) }
                )
              }
            }
          }
        }

        2 -> {
          SmartStatsView(
            stats = uiState.stats,
            modifier = Modifier
              .weight(1f)
              .verticalScroll(rememberScrollState())
              .padding(20.dp)
          )
        }
      }
    }
  }

  if (showAddWordDialog) {
    var wordInput by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showAddWordDialog = false },
      properties = DialogProperties(dismissOnBackPress = false),
      title = { Text("Añadir palabra al vocabulario") },
      text = {
        Column {
          LettorImeBackDialogHandler(onDismiss = { showAddWordDialog = false })
          OutlinedTextField(
            value = wordInput,
            onValueChange = { wordInput = it },
            label = { Text("Palabra (ej. hidalgo, sosiego…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("manual_vocab_input")
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (wordInput.isNotBlank()) {
              viewModel.addManualVocabularyWord(wordInput)
            }
            showAddWordDialog = false
          }
        ) { Text("Guardar") }
      },
      dismissButton = {
        TextButton(onClick = { showAddWordDialog = false }) { Text("Cancelar") }
      }
    )
  }
}

@Composable
private fun HighlightCard(
  item: HighlightWithBook,
  onClick: () -> Unit,
  onDelete: () -> Unit
) {
  val dateLabel = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es"))
    .format(Date(item.highlight.createdAt))

  ElevatedCard(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(item.highlight.color.dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
          imageVector = Icons.Default.StickyNote2,
          contentDescription = null,
          tint = item.highlight.color.dotColor,
          modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = item.bookTitle,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        FormatTagPill(format = item.bookFormat)
        IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar resaltado")
        }
      }

      Text(
        text = "${if (item.highlight.isStickyNoteOnly) "Nota adhesiva" else "Resaltado ${item.highlight.color.label}"} · Unidad ${item.highlight.unitIndex + 1} · $dateLabel",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (item.highlight.excerpt.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Surface(
          color = item.highlight.color.washLight,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "«${item.highlight.excerpt}»",
            style = MaterialTheme.typography.bodyMedium.copy(
              fontFamily = LiterataFamily,
              fontStyle = FontStyle.Italic
            ),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp)
          )
        }
      }

      if (item.highlight.stickyNote.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = "Nota: ${item.highlight.stickyNote}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}

@Composable
private fun BookmarkCard(
  item: BookmarkWithBook,
  onClick: () -> Unit,
  onDelete: () -> Unit
) {
  val dateLabel = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es"))
    .format(Date(item.bookmark.createdAt))

  ElevatedCard(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("bookmark_card_${item.bookmark.id}")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Filled.Bookmark,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = item.bookTitle,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        FormatTagPill(format = item.bookFormat)
        IconButton(
          onClick = onDelete,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("delete_bookmark_${item.bookmark.id}")
        ) {
          Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = "Eliminar marcador",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Text(
        text = "${item.bookmark.label} · $dateLabel",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (item.bookmark.excerpt.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "«${item.bookmark.excerpt}»",
            style = MaterialTheme.typography.bodyMedium.copy(
              fontFamily = LiterataFamily,
              fontStyle = FontStyle.Italic
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp)
          )
        }
      }

      if (item.bookmark.note.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(text = item.bookmark.note, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun VocabularyCard(
  entry: VocabularyEntity,
  onToggleMastered: () -> Unit,
  onDelete: () -> Unit
) {
  ElevatedCard(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = entry.word,
              style = MaterialTheme.typography.titleMedium.copy(fontFamily = PlayfairDisplayFamily)
            )
            if (entry.partOfSpeech.isNotBlank()) {
              Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp)
              ) {
                Text(
                  text = entry.partOfSpeech,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
            }
          }
          if (entry.translation.isNotBlank()) {
            Text(
              text = "EN: ${entry.translation}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
          }
        }
        IconButton(onClick = onToggleMastered, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(
            imageVector = if (entry.isMastered) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
            contentDescription = if (entry.isMastered) "Dominada" else "Marcar como aprendida",
            tint = if (entry.isMastered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar palabra")
        }
      }

      Spacer(Modifier.height(6.dp))
      Text(text = entry.definition, style = MaterialTheme.typography.bodyMedium)

      if (entry.contextSnippet.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
          text = "«${entry.contextSnippet}»",
          style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      if (entry.bookTitle.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
          text = entry.bookTitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun SmartStatsView(
  stats: ReadingStats,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Daily goal progress card
    val goalPct = (stats.todayMinutes.toFloat() / stats.dailyGoalMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(18.dp)) {
        Text(
          text = "META DIARIA Y RACHA",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Bottom
        ) {
          Text(
            text = "${stats.todayMinutes} / ${stats.dailyGoalMinutes} min hoy",
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlayfairDisplayFamily)
          )
          Text(
            text = "Racha: ${stats.streakDays} día(s)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
          )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = { goalPct.coerceAtLeast(0.02f) },
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
        )
      }
    }

    // Core KPI grid
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatChipCard("${stats.totalBooks}", "Libros", Modifier.weight(1f))
      StatChipCard("${stats.finishedBooks}", "Terminados", Modifier.weight(1f))
      StatChipCard("${stats.totalReadingMinutes}m", "Total leído", Modifier.weight(1f))
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatChipCard("${stats.estimatedWpm}", "Palabras / min", Modifier.weight(1f))
      StatChipCard("${stats.totalAnnotations}", "Notas y citas", Modifier.weight(1f))
      StatChipCard("${stats.vocabularyCount}", "Vocabulario", Modifier.weight(1f))
    }

    if (stats.topGenres.isNotEmpty()) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "CLASIFICACIÓN POR GÉNEROS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
          stats.topGenres.forEach { (genre, count) ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = genre, style = MaterialTheme.typography.bodyMedium)
              Text(
                text = "$count vol.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }

    if (stats.topAuthors.isNotEmpty()) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "AUTORES EN TU ESTANTERÍA",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
          stats.topAuthors.forEach { (author, count) ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = "$count vol.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatChipCard(
  value: String,
  label: String,
  modifier: Modifier = Modifier
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(14.dp),
    tonalElevation = 1.dp,
    modifier = modifier
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.primary
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
      )
    }
  }
}

@Composable
private fun EmptyStudyTabState(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String
) {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .widthIn(max = 320.dp)
        .padding(24.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier = Modifier.size(56.dp)
      )
      Spacer(Modifier.height(14.dp))
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(6.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
