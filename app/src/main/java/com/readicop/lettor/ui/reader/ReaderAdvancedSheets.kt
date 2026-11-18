package com.readicop.lettor.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readicop.lettor.data.local.HighlightColor
import com.readicop.lettor.domain.engine.ChapterSummaryResult
import com.readicop.lettor.domain.engine.DictionaryEntry
import com.readicop.lettor.domain.engine.LocalDictionaryAndTranslator
import com.readicop.lettor.ui.theme.LiterataFamily
import com.readicop.lettor.ui.theme.PlayfairDisplayFamily

/**
 * Interactive fragment toolbox opened when long-pressing a paragraph or tapping Text Tools:
 * - Multi-color highlighter (5 colors) + Sticky note
 * - Copy & Share quote
 * - Integrated offline dictionary + Vocabulary saver
 * - Offline phrase translator + browser fallback
 * - Start TTS from this paragraph
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FragmentToolsSheet(
  bookTitle: String,
  bookAuthor: String,
  fragmentText: String,
  blockIndex: Int,
  onSaveHighlight: (stickyNote: String, color: HighlightColor, isStickyOnly: Boolean) -> Unit,
  onReadAloudFromHere: (() -> Unit)?,
  onLookupWord: (String) -> DictionaryEntry,
  onSaveWordToVocabulary: (word: String, onDone: (Boolean) -> Unit) -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  var selectedTab by remember { mutableIntStateOf(0) }
  var selectedColor by remember { mutableStateOf(HighlightColor.AMBER) }
  var stickyNote by remember { mutableStateOf("") }
  var statusFeedback by remember { mutableStateOf<String?>(null) }

  val notableWords = remember(fragmentText) {
    LocalDictionaryAndTranslator.extractNotableWords(fragmentText)
  }
  var wordQuery by remember { mutableStateOf(notableWords.firstOrNull().orEmpty()) }
  var activeDictEntry by remember(wordQuery) {
    mutableStateOf(if (wordQuery.isNotBlank()) onLookupWord(wordQuery) else null)
  }
  val translationResult = remember(fragmentText) {
    LocalDictionaryAndTranslator.translateLocally(fragmentText)
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 28.dp)
    ) {
      Text(
        text = "Herramientas del fragmento",
        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlayfairDisplayFamily)
      )
      Spacer(Modifier.height(8.dp))

      Surface(
        color = selectedColor.washLight,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "«${fragmentText.take(220)}${if (fragmentText.length > 220) "…" else ""}»",
          style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = LiterataFamily,
            fontStyle = FontStyle.Italic
          ),
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(12.dp)
        )
      }

      statusFeedback?.let { msg ->
        Spacer(Modifier.height(8.dp))
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = msg,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
          )
        }
      }

      Spacer(Modifier.height(10.dp))
      TabRow(selectedTabIndex = selectedTab) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Resaltar y Nota") })
        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Diccionario") })
        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Traducir") })
      }

      Spacer(Modifier.height(14.dp))

      when (selectedTab) {
        0 -> {
          Text(
            text = "COLOR DEL RESALTADOR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
          Spacer(Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            HighlightColor.entries.forEach { color ->
              val isSelected = selectedColor == color
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                  .clickable { selectedColor = color }
                  .testTag("highlight_color_${color.name.lowercase()}")
              ) {
                Box(
                  modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.dotColor)
                    .border(
                      width = if (isSelected) 3.dp else 0.dp,
                      color = MaterialTheme.colorScheme.onSurface,
                      shape = CircleShape
                    )
                )
                Spacer(Modifier.height(2.dp))
                Text(color.label, style = MaterialTheme.typography.labelSmall)
              }
            }
          }

          Spacer(Modifier.height(14.dp))
          OutlinedTextField(
            value = stickyNote,
            onValueChange = { stickyNote = it },
            label = { Text("Nota adhesiva (opcional)") },
            placeholder = { Text("Añade una reflexión sobre este pasaje…") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("fragment_sticky_note_input")
          )

          Spacer(Modifier.height(12.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Button(
              onClick = {
                onSaveHighlight(stickyNote, selectedColor, blockIndex < 0 && stickyNote.isNotBlank())
                onDismiss()
              },
              modifier = Modifier.weight(1f).testTag("save_highlight_button")
            ) {
              Text(if (blockIndex >= 0) "Guardar resaltado" else "Guardar nota adhesiva")
            }
          }

          Spacer(Modifier.height(12.dp))
          HorizontalDivider()
          Spacer(Modifier.height(12.dp))

          // Copy, Share, and Read from here
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Fragmento de $bookTitle", fragmentText))
                statusFeedback = "Fragmento copiado al portapapeles"
              },
              modifier = Modifier.testTag("copy_fragment_button")
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Copiar")
            }

            OutlinedButton(
              onClick = {
                val shareBody = "«$fragmentText»\n\n— $bookTitle ($bookAuthor), vía Lettor"
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_TEXT, shareBody)
                }
                context.startActivity(Intent.createChooser(intent, "Compartir fragmento"))
              },
              modifier = Modifier.testTag("share_fragment_button")
            ) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Compartir")
            }

            if (onReadAloudFromHere != null) {
              FilledTonalButton(
                onClick = {
                  onReadAloudFromHere()
                  onDismiss()
                },
                modifier = Modifier.testTag("tts_from_here_button")
              ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Escuchar desde aquí")
              }
            }
          }
        }

        1 -> {
          // Integrated Dictionary + Vocabulary Saver
          OutlinedTextField(
            value = wordQuery,
            onValueChange = { wordQuery = it },
            label = { Text("Consultar palabra") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("dictionary_word_input")
          )
          if (notableWords.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              notableWords.forEach { w ->
                AssistChip(
                  onClick = { wordQuery = w },
                  label = { Text(w) }
                )
              }
            }
          }

          activeDictEntry?.let { entry ->
            Spacer(Modifier.height(12.dp))
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = entry.word.replaceFirstChar { it.titlecase() },
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = PlayfairDisplayFamily)
                  )
                  Text(
                    text = entry.partOfSpeech,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                  )
                }
                Text(text = entry.definition, style = MaterialTheme.typography.bodyMedium)
                if (entry.synonyms.isNotEmpty()) {
                  Text(
                    text = "Sinónimos: ${entry.synonyms.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                if (entry.englishTranslation.isNotBlank()) {
                  Text(
                    text = "Inglés: ${entry.englishTranslation}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                  )
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Button(
                    onClick = {
                      onSaveWordToVocabulary(entry.word) { added ->
                        statusFeedback = if (added) {
                          "«${entry.word}» guardada en tu Vocabulario"
                        } else {
                          "«${entry.word}» ya estaba en tu Vocabulario"
                        }
                      }
                    },
                    modifier = Modifier.testTag("save_to_vocab_button")
                  ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Guardar vocabulario")
                  }
                  OutlinedButton(
                    onClick = { LocalDictionaryAndTranslator.openExternalDictionary(context, entry.word) }
                  ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("DLE")
                  }
                }
              }
            }
          }
        }

        2 -> {
          // Offline Translator + browser fallback
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                  text = translationResult.targetLanguageLabel,
                  style = MaterialTheme.typography.titleSmall
                )
              }
              Text(
                text = translationResult.translatedText,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = LiterataFamily)
              )
              Text(
                text = translationResult.coverageNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                  onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Traducción", translationResult.translatedText))
                    statusFeedback = "Traducción copiada"
                  }
                ) {
                  Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Copiar")
                }
                FilledTonalButton(
                  onClick = { LocalDictionaryAndTranslator.openExternalTranslator(context, fragmentText) }
                ) {
                  Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Traductor completo")
                }
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
fun ChapterSummarySheet(
  summary: ChapterSummaryResult,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
          text = "Resumen local del capítulo",
          style = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlayfairDisplayFamily)
        )
      }
      Text(
        text = "${summary.chapterTitle} · ${summary.wordCount} palabras · ~${summary.estimatedMinutes} min",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (summary.topKeywords.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          summary.topKeywords.forEach { kw ->
            AssistChip(onClick = {}, label = { Text(kw) })
          }
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          summary.keySentences.forEach { sentence ->
            Text(
              text = "• $sentence",
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LiterataFamily)
            )
          }
        }
      }

      Text(
        text = "Generado 100% en tu dispositivo mediante análisis extractivo local (sin conexión).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
        Text("Cerrar")
      }
    }
  }
}
