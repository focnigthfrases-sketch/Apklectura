package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.BookEntity
import com.example.data.local.BookFormat
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PlayfairDisplayFamily
import java.io.File
import kotlin.math.abs

private val CoverPalettes = listOf(
  Pair(Color(0xFF2E3A47), Color(0xFFF3E9D8)),
  Pair(Color(0xFF5D2A24), Color(0xFFF9EFE4)),
  Pair(Color(0xFF2D4538), Color(0xFFEDE8DC)),
  Pair(Color(0xFF47372E), Color(0xFFF5EBDD)),
  Pair(Color(0xFF343049), Color(0xFFF2ECE2)),
  Pair(Color(0xFF6B4226), Color(0xFFFAF2E6))
)

@Composable
fun BookCoverArt(
  book: BookEntity,
  modifier: Modifier = Modifier,
  showFormatBadge: Boolean = true
) {
  val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
  val hasValidCover = book.coverPath != null && File(book.coverPath).exists()
  val palette = CoverPalettes[abs(book.title.hashCode()) % CoverPalettes.size]

  Box(
    modifier = modifier
      .aspectRatio(0.68f)
      .clip(shape)
      .background(palette.first)
      .border(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape)
  ) {
    if (hasValidCover) {
      AsyncImage(
        model = File(book.coverPath!!),
        contentDescription = "Portada de ${book.title}",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      // Classic clothbound typographic cover fallback
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(palette.second.copy(alpha = 0.45f))
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
          Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium.copy(
              fontFamily = PlayfairDisplayFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              lineHeight = 19.sp
            ),
            color = palette.second,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = book.author,
            style = MaterialTheme.typography.bodySmall,
            color = palette.second.copy(alpha = 0.78f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.second.copy(alpha = 0.35f))
        )
      }
    }

    // Subtle book spine crease shadow on the left edge
    Box(
      modifier = Modifier
        .fillMaxHeight()
        .width(12.dp)
        .align(Alignment.CenterStart)
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              Color.Black.copy(alpha = 0.32f),
              Color.White.copy(alpha = 0.14f),
              Color.Transparent
            )
          )
        )
    )

    if (showFormatBadge) {
      val badgeColor = if (book.format == BookFormat.EPUB) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.secondary
      }
      Surface(
        color = badgeColor.copy(alpha = 0.92f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(7.dp)
      ) {
        Text(
          text = book.format.name,
          style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
          ),
          color = Color.White,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
      }
    }
  }
}

@Composable
fun FormatTagPill(format: BookFormat, modifier: Modifier = Modifier) {
  val bg = if (format == BookFormat.EPUB) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.secondaryContainer
  }
  val fg = if (format == BookFormat.EPUB) {
    MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSecondaryContainer
  }
  Surface(
    color = bg,
    shape = RoundedCornerShape(6.dp),
    modifier = modifier
  ) {
    Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) {
      Text(
        text = format.name,
        style = MaterialTheme.typography.labelSmall.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold
        ),
        color = fg
      )
    }
  }
}
