package com.readicop.lettor.ui.library

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.readicop.lettor.notifications.ToucanArtwork
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.readicop.lettor.R
import com.readicop.lettor.data.local.BookEntity
import com.readicop.lettor.data.local.BookFormat
import com.readicop.lettor.domain.engine.LocalBookIntelligence
import com.readicop.lettor.monetization.AdMobManager
import com.readicop.lettor.monetization.LibraryAdaptiveBanner
import com.readicop.lettor.ui.components.BookCoverArt
import com.readicop.lettor.ui.components.FormatTagPill
import com.readicop.lettor.ui.theme.JetBrainsMonoFamily
import com.readicop.lettor.ui.theme.PlayfairDisplayFamily
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  viewModel: LibraryViewModel,
  adMobManager: AdMobManager,
  onOpenBook: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val activity = context as? Activity

  var isSearchVisible by rememberSaveable { mutableStateOf(false) }
  var showTaxonomyRow by rememberSaveable { mutableStateOf(false) }
  var showSortMenu by remember { mutableStateOf(false) }
  var selectedBookForSheet by remember { mutableStateOf<BookEntity?>(null) }
  var bookToEdit by remember { mutableStateOf<BookEntity?>(null) }
  var bookPendingDelete by remember { mutableStateOf<BookEntity?>(null) }

  // Multi-book import via zero-permission Storage Access Framework
  val openMultipleBooksLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments()
  ) { uris: List<Uri> ->
    if (uris.isNotEmpty()) {
      viewModel.importMultipleBooks(uris)
    }
  }

  // Contextual notification permission prompt (only offered after reading, never at first launch)
  var permissionGrantedState by remember {
    mutableStateOf(
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    )
  }
  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    permissionGrantedState = granted
    viewModel.markNotificationRationaleShown()
  }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        is LibraryEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
        is LibraryEvent.ImportCompletedSuccessfully -> {
          // AdMob rule: ONLY after import completes successfully, max 3/day, never blocking
          adMobManager.onImportCompletedSuccessfully(activity) {
            // Continue normally regardless of whether ad showed or failed to load
          }
          snackbarHostState.showSnackbar(event.summaryMessage)
        }
      }
    }
  }

  val launchPicker = {
    openMultipleBooksLauncher.launch(
      arrayOf(
        "application/epub+zip",
        "application/pdf",
        "application/octet-stream"
      )
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize()
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      // Editorial Top Bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.semantics { heading() }) {
          Text(
            text = "Lettor",
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = PlayfairDisplayFamily),
            color = MaterialTheme.colorScheme.onBackground
          )
          Text(
            text = "${uiState.allBooks.size} ${if (uiState.allBooks.size == 1) "volumen" else "volúmenes"} · ${uiState.availableAuthors.size} autores",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = { showTaxonomyRow = !showTaxonomyRow },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("library_taxonomy_toggle")
          ) {
            Icon(
              imageVector = Icons.Default.Tag,
              contentDescription = "Filtrar por autor o género",
              tint = if (showTaxonomyRow || uiState.selectedAuthor != null || uiState.selectedGenre != null) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              }
            )
          }

          IconButton(
            onClick = {
              isSearchVisible = !isSearchVisible
              if (!isSearchVisible) viewModel.setSearchQuery("")
            },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("library_search_toggle")
          ) {
            Icon(
              imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
              contentDescription = "Buscar en biblioteca"
            )
          }

          Box {
            IconButton(
              onClick = { showSortMenu = true },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("library_sort_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Ordenar libros"
              )
            }
            DropdownMenu(
              expanded = showSortMenu,
              onDismissRequest = { showSortMenu = false }
            ) {
              LibrarySort.entries.forEach { sortOption ->
                DropdownMenuItem(
                  text = {
                    Text(
                      text = sortOption.label,
                      fontWeight = if (uiState.sort == sortOption) FontWeight.Bold else FontWeight.Normal,
                      color = if (uiState.sort == sortOption) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                  },
                  onClick = {
                    viewModel.setSort(sortOption)
                    showSortMenu = false
                  }
                )
              }
            }
          }

          IconButton(
            onClick = viewModel::toggleGridView,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("library_view_toggle")
          ) {
            Icon(
              imageVector = if (uiState.isGridView) Icons.Default.ViewAgenda else Icons.Default.GridView,
              contentDescription = if (uiState.isGridView) "Vista en lista" else "Vista en cuadrícula"
            )
          }
        }
      }

      AnimatedVisibility(
        visible = isSearchVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
      ) {
        OutlinedTextField(
          value = uiState.searchQuery,
          onValueChange = viewModel::setSearchQuery,
          placeholder = { Text("Buscar título, autor o género…") },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          trailingIcon = {
            if (uiState.searchQuery.isNotEmpty()) {
              IconButton(onClick = { viewModel.setSearchQuery("") }) {
                Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 10.dp)
            .testTag("library_search_input")
        )
      }

      // Main filter chips
      LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp)
      ) {
        items(LibraryFilter.entries) { filter ->
          val selected = uiState.filter == filter
          FilterChip(
            selected = selected,
            onClick = { viewModel.setFilter(filter) },
            label = { Text(filter.label) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
          )
        }
      }

      // Expandable Author & Genre taxonomy bar
      AnimatedVisibility(
        visible = showTaxonomyRow || uiState.selectedAuthor != null || uiState.selectedGenre != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
      ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
          if (uiState.availableGenres.isNotEmpty()) {
            LazyRow(
              contentPadding = PaddingValues(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(uiState.availableGenres) { genre ->
                val selected = uiState.selectedGenre == genre
                FilterChip(
                  selected = selected,
                  onClick = { viewModel.selectGenre(genre) },
                  leadingIcon = {
                    Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size(16.dp))
                  },
                  label = { Text(genre) },
                  modifier = Modifier.testTag("genre_chip_$genre")
                )
              }
            }
          }
          if (uiState.availableAuthors.isNotEmpty()) {
            LazyRow(
              contentPadding = PaddingValues(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(uiState.availableAuthors) { author ->
                val selected = uiState.selectedAuthor == author
                FilterChip(
                  selected = selected,
                  onClick = { viewModel.selectAuthor(author) },
                  leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                  },
                  label = { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  modifier = Modifier.testTag("author_chip_$author")
                )
              }
            }
          }
        }
      }

      // Unexpected exit recovery banner
      uiState.crashRecoveryBook?.let { recoveryBook ->
        Surface(
          color = MaterialTheme.colorScheme.tertiaryContainer,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 10.dp)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "¿Retomar lectura interrumpida?",
                style = MaterialTheme.typography.titleSmall
              )
              Text(
                text = "«${recoveryBook.title}» se cerró sin finalizar la sesión.",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            TextButton(
              onClick = { onOpenBook(recoveryBook.id) },
              modifier = Modifier.testTag("recovery_resume_button")
            ) {
              Text("Retomar")
            }
            IconButton(onClick = viewModel::dismissCrashRecoveryBanner) {
              Icon(Icons.Default.Close, contentDescription = "Descartar aviso")
            }
          }
        }
      }

      // Contextual post-reading notification rationale (never shown on first launch)
      val shouldShowNotifExplanation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !permissionGrantedState &&
        uiState.preferences.hasOpenedAnyBook &&
        !uiState.preferences.notificationRationaleShown &&
        uiState.preferences.resumeReminderEnabled
      if (shouldShowNotifExplanation) {
        val toucanBmp = remember(context) { ToucanArtwork.getOrCreateBitmap(context).asImageBitmap() }
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 10.dp)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Image(
              bitmap = toucanBmp,
              contentDescription = "Mascota tucán de Lettor",
              modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "¿Avisarte si dejas un libro a medias?",
                style = MaterialTheme.typography.titleSmall
              )
              Text(
                text = "Recibe un aviso amable con tu capítulo real si pasa más de un día sin leer (nunca de madrugada).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
              )
              Spacer(Modifier.height(6.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                  },
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                  modifier = Modifier.testTag("library_enable_notifications_button")
                ) {
                  Text("Activar")
                }
                TextButton(
                  onClick = viewModel::markNotificationRationaleShown,
                  modifier = Modifier.testTag("library_dismiss_notifications_button")
                ) {
                  Text("Ahora no")
                }
              }
            }
          }
        }
      }

      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        if (uiState.isLoading) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        } else if (uiState.filteredBooks.isEmpty()) {
          EmptyLibraryState(
            hasAnyBooks = uiState.allBooks.isNotEmpty(),
            onImportClick = launchPicker,
            onClearFilters = viewModel::clearAllFilters,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val showHero = uiState.continueReadingBook != null &&
            uiState.filter == LibraryFilter.ALL &&
            uiState.selectedAuthor == null &&
            uiState.selectedGenre == null &&
            uiState.searchQuery.isBlank()

          if (uiState.isGridView) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              val gridColumns = if (maxWidth < 600.dp) {
                GridCells.Fixed(3)
              } else {
                GridCells.Adaptive(minSize = 136.dp)
              }
              LazyVerticalGrid(
                columns = gridColumns,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 76.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
              ) {
                if (showHero && uiState.continueReadingBook != null) {
                  item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                      DailyReadingGoalBanner(
                        todaySeconds = uiState.preferences.todayReadingSeconds,
                        goalMinutes = uiState.preferences.dailyGoalMinutes,
                        streakDays = uiState.preferences.readingStreakDays,
                        onSelectGoal = viewModel::setDailyGoalMinutes
                      )
                      ContinueReadingHeroCard(
                        book = uiState.continueReadingBook!!,
                        wpm = uiState.preferences.estimatedWpm,
                        onClick = { onOpenBook(uiState.continueReadingBook!!.id) }
                      )
                    }
                  }
                }

                items(uiState.filteredBooks, key = { it.id }) { book ->
                  BookGridCard(
                    book = book,
                    onClick = { onOpenBook(book.id) },
                    onMenuClick = { selectedBookForSheet = book },
                    onFavoriteToggle = { viewModel.toggleFavorite(book) }
                  )
                }
              }
            }
          } else {
            LazyColumn(
              contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 76.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              if (showHero && uiState.continueReadingBook != null) {
                item(key = "hero_goal") {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DailyReadingGoalBanner(
                      todaySeconds = uiState.preferences.todayReadingSeconds,
                      goalMinutes = uiState.preferences.dailyGoalMinutes,
                      streakDays = uiState.preferences.readingStreakDays,
                      onSelectGoal = viewModel::setDailyGoalMinutes
                    )
                    ContinueReadingHeroCard(
                      book = uiState.continueReadingBook!!,
                      wpm = uiState.preferences.estimatedWpm,
                      onClick = { onOpenBook(uiState.continueReadingBook!!.id) }
                    )
                  }
                  Spacer(Modifier.height(4.dp))
                }
              }

              items(uiState.filteredBooks, key = { it.id }) { book ->
                BookListRowCard(
                  book = book,
                  wpm = uiState.preferences.estimatedWpm,
                  onClick = { onOpenBook(book.id) },
                  onMenuClick = { selectedBookForSheet = book },
                  onFavoriteToggle = { viewModel.toggleFavorite(book) }
                )
              }
            }
          }
        }

        SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 8.dp)
        )

        // Compact + button placed directly above the AdMob banner (never overlapping it)
        FloatingActionButton(
          onClick = launchPicker,
          shape = CircleShape,
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 12.dp)
            .size(48.dp)
            .testTag("import_book_fab")
        ) {
          if (uiState.isImporting) {
            CircularProgressIndicator(
              modifier = Modifier.size(22.dp),
              strokeWidth = 2.5.dp,
              color = MaterialTheme.colorScheme.onPrimary
            )
          } else {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = "Importar libros",
              modifier = Modifier.size(32.dp)
            )
          }
        }
      }

      // Adaptive AdMob Banner flush at the bottom right above the NavigationBar
      LibraryAdaptiveBanner(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
      )
    }
  }

  // Book options sheet
  selectedBookForSheet?.let { book ->
    ModalBottomSheet(onDismissRequest = { selectedBookForSheet = null }) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 28.dp)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          BookCoverArt(
            book = book,
            modifier = Modifier.width(64.dp),
            showFormatBadge = false
          )
          Spacer(Modifier.width(16.dp))
          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FormatTagPill(format = book.format)
              Text(
                text = book.genre,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
              )
            }
            Spacer(Modifier.height(4.dp))
            Text(
              text = book.title,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = book.author,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
              text = formatBookMetaSummary(book, uiState.preferences.estimatedWpm),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        ListItem(
          headlineContent = { Text("Abrir libro") },
          leadingContent = { Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null) },
          modifier = Modifier
            .clickable {
              selectedBookForSheet = null
              onOpenBook(book.id)
            }
            .testTag("sheet_open_book")
        )

        ListItem(
          headlineContent = { Text("Editar portada y metadatos") },
          leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
          modifier = Modifier
            .clickable {
              bookToEdit = book
              selectedBookForSheet = null
            }
            .testTag("sheet_edit_metadata")
        )

        ListItem(
          headlineContent = {
            Text(if (book.isFavorite) "Quitar de favoritos" else "Añadir a favoritos")
          },
          leadingContent = {
            Icon(
              imageVector = if (book.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
              contentDescription = null,
              tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
          },
          modifier = Modifier
            .clickable {
              viewModel.toggleFavorite(book)
              selectedBookForSheet = null
            }
            .testTag("sheet_toggle_favorite")
        )

        ListItem(
          headlineContent = {
            Text(if (book.isFinished) "Marcar como pendiente" else "Marcar como terminado")
          },
          leadingContent = {
            Icon(
              imageVector = if (book.isFinished) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
              contentDescription = null
            )
          },
          modifier = Modifier
            .clickable {
              viewModel.toggleFinished(book)
              selectedBookForSheet = null
            }
            .testTag("sheet_toggle_finished")
        )

        ListItem(
          headlineContent = { Text("Eliminar de la biblioteca", color = MaterialTheme.colorScheme.error) },
          leadingContent = {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
          },
          modifier = Modifier
            .clickable {
              bookPendingDelete = book
              selectedBookForSheet = null
            }
            .testTag("sheet_delete_book")
        )
      }
    }
  }

  bookToEdit?.let { book ->
    EditBookMetadataDialog(
      book = book,
      onSave = { title, author, genre, description, coverUri ->
        viewModel.updateBookMetadata(book, title, author, genre, description, coverUri)
        bookToEdit = null
      },
      onDismiss = { bookToEdit = null }
    )
  }

  bookPendingDelete?.let { book ->
    AlertDialog(
      onDismissRequest = { bookPendingDelete = null },
      title = { Text("Eliminar libro") },
      text = { Text("¿Quieres eliminar «${book.title}» y todas sus notas, resaltados y marcadores?") },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteBook(book)
            bookPendingDelete = null
          },
          modifier = Modifier.testTag("confirm_delete_book")
        ) {
          Text("Eliminar", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { bookPendingDelete = null }) {
          Text("Cancelar")
        }
      }
    )
  }
}

@Composable
private fun DailyReadingGoalBanner(
  todaySeconds: Long,
  goalMinutes: Int,
  streakDays: Int,
  onSelectGoal: (Int) -> Unit
) {
  val todayMinutes = (todaySeconds / 60L).toInt()
  val progress = (todayMinutes.toFloat() / goalMinutes.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
  var showGoalPicker by remember { mutableStateOf(false) }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .clickable { showGoalPicker = true }
      .testTag("daily_goal_banner")
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = "Objetivo diario: $todayMinutes / $goalMinutes min",
            style = MaterialTheme.typography.titleSmall
          )
        }
        if (streakDays > 0) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp)
          ) {
            Text(
              text = "$streakDays d racha",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }
        } else {
          Text(
            text = "Cambiar meta",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
      Spacer(Modifier.height(8.dp))
      LinearProgressIndicator(
        progress = { progress.coerceAtLeast(0.02f) },
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp)),
        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
      )
    }
  }

  if (showGoalPicker) {
    AlertDialog(
      onDismissRequest = { showGoalPicker = false },
      title = { Text("Objetivo diario de lectura") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "Elige cuántos minutos quieres dedicar a leer cada día:",
            style = MaterialTheme.typography.bodySmall
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(10, 20, 30, 45).forEach { mins ->
              FilterChip(
                selected = goalMinutes == mins,
                onClick = {
                  onSelectGoal(mins)
                  showGoalPicker = false
                },
                label = { Text("${mins}m") },
                modifier = Modifier.weight(1f)
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showGoalPicker = false }) { Text("Cerrar") }
      }
    )
  }
}

@Composable
private fun ContinueReadingHeroCard(
  book: BookEntity,
  wpm: Int,
  onClick: () -> Unit
) {
  val percent = (book.progressFraction * 100).roundToInt().coerceIn(0, 100)
  val remainingMin = LocalBookIntelligence.estimateRemainingMinutes(
    book.totalWordCount, book.totalUnits, book.progressFraction, wpm
  )
  val unitLabel = if (book.format == BookFormat.EPUB) {
    "Cap. ${book.currentUnit + 1}/${book.totalUnits}"
  } else {
    "Pág. ${book.currentUnit + 1}/${book.totalUnits}"
  }

  ElevatedCard(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ),
    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("continue_reading_card")
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            colors = listOf(
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
              MaterialTheme.colorScheme.surfaceContainerLow
            )
          )
        )
        .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        BookCoverArt(
          book = book,
          modifier = Modifier.width(50.dp),
          showFormatBadge = false
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "CONTINUAR LEYENDO",
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold
              ),
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              text = if (book.isFinished) "Completado" else "$unitLabel · ~$remainingMin min",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Spacer(Modifier.height(2.dp))
          Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium.copy(
              fontFamily = PlayfairDisplayFamily,
              fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "${book.author} · ${book.genre}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            LinearProgressIndicator(
              progress = { book.progressFraction.coerceIn(0.02f, 1f) },
              modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
              trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
            Surface(
              color = MaterialTheme.colorScheme.primary,
              shape = RoundedCornerShape(8.dp)
            ) {
              Text(
                text = "Leer ($percent%)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BookGridCard(
  book: BookEntity,
  onClick: () -> Unit,
  onMenuClick: () -> Unit,
  onFavoriteToggle: () -> Unit
) {
  val percent = (book.progressFraction * 100).roundToInt().coerceIn(0, 100)

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .testTag("book_card_${book.id}")
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      BookCoverArt(
        book = book,
        modifier = Modifier.fillMaxWidth(),
        compact = true
      )

      Box(
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .size(36.dp)
          .clip(CircleShape)
          .clickable(
            role = Role.Button,
            onClick = onFavoriteToggle
          )
          .padding(4.dp)
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
          tonalElevation = 1.dp,
          modifier = Modifier.size(22.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = if (book.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
              contentDescription = "Favorito",
              tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(13.dp)
            )
          }
        }
      }

      Box(
        contentAlignment = Alignment.BottomEnd,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .size(36.dp)
          .clip(CircleShape)
          .clickable(
            role = Role.Button,
            onClick = onMenuClick
          )
          .padding(4.dp)
          .testTag("book_menu_${book.id}")
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
          tonalElevation = 1.dp,
          modifier = Modifier.size(22.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.MoreVert,
              contentDescription = "Opciones de ${book.title}",
              modifier = Modifier.size(13.dp)
            )
          }
        }
      }
    }

    Spacer(Modifier.height(6.dp))

    Text(
      text = book.title,
      style = MaterialTheme.typography.titleSmall.copy(
        fontSize = 12.5.sp,
        lineHeight = 16.sp
      ),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )
    Text(
      text = book.author,
      style = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp
      ),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )

    Spacer(Modifier.height(5.dp))

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      LinearProgressIndicator(
        progress = { book.progressFraction.coerceIn(0f, 1f) },
        modifier = Modifier
          .weight(1f)
          .height(3.5.dp)
          .clip(RoundedCornerShape(2.dp)),
        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
      )
      Text(
        text = if (book.isFinished) "Leído" else "$percent%",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = if (book.isFinished) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
      )
    }
  }
}

@Composable
private fun BookListRowCard(
  book: BookEntity,
  wpm: Int,
  onClick: () -> Unit,
  onMenuClick: () -> Unit,
  onFavoriteToggle: () -> Unit
) {
  val percent = (book.progressFraction * 100).roundToInt().coerceIn(0, 100)
  val remainingMin = LocalBookIntelligence.estimateRemainingMinutes(
    book.totalWordCount, book.totalUnits, book.progressFraction, wpm
  )

  ElevatedCard(
    onClick = onClick,
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("book_list_item_${book.id}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      BookCoverArt(
        book = book,
        modifier = Modifier.width(62.dp),
        showFormatBadge = false
      )
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FormatTagPill(format = book.format)
          Text(
            text = book.genre,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
          )
          Text(
            text = if (book.isFinished) "Completado" else "$percent% · ~$remainingMin min",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(Modifier.height(4.dp))
        Text(
          text = book.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = book.author,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { book.progressFraction.coerceIn(0f, 1f) },
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
          trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onFavoriteToggle, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(
            imageVector = if (book.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = "Favorito",
            tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        IconButton(onClick = onMenuClick, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Default.MoreVert, contentDescription = "Opciones")
        }
      }
    }
  }
}

@Composable
private fun EditBookMetadataDialog(
  book: BookEntity,
  onSave: (title: String, author: String, genre: String, description: String, coverUri: Uri?) -> Unit,
  onDismiss: () -> Unit
) {
  var title by remember { mutableStateOf(book.title) }
  var author by remember { mutableStateOf(book.author) }
  var genre by remember { mutableStateOf(book.genre) }
  var description by remember { mutableStateOf(book.description) }
  var selectedCoverUri by remember { mutableStateOf<Uri?>(null) }

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    if (uri != null) selectedCoverUri = uri
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Editar portada y metadatos") },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          if (selectedCoverUri != null) {
            AsyncImage(
              model = selectedCoverUri,
              contentDescription = "Nueva portada",
              contentScale = ContentScale.Crop,
              modifier = Modifier
                .width(58.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(8.dp))
            )
          } else {
            BookCoverArt(book = book, modifier = Modifier.width(58.dp), showFormatBadge = false)
          }
          OutlinedButton(
            onClick = {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            },
            modifier = Modifier.testTag("change_cover_button")
          ) {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cambiar portada")
          }
        }

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Título") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_title_input")
        )
        OutlinedTextField(
          value = author,
          onValueChange = { author = it },
          label = { Text("Autor") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_author_input")
        )
        OutlinedTextField(
          value = genre,
          onValueChange = { genre = it },
          label = { Text("Género") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_genre_input")
        )
        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Sinopsis") },
          minLines = 2,
          maxLines = 4,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onSave(title, author, genre, description, selectedCoverUri) },
        modifier = Modifier.testTag("save_metadata_button")
      ) {
        Text("Guardar")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancelar") }
    }
  )
}

@Composable
private fun EmptyLibraryState(
  hasAnyBooks: Boolean,
  onImportClick: () -> Unit,
  onClearFilters: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .widthIn(max = 380.dp)
        .padding(24.dp)
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_empty_library),
        contentDescription = "Ilustración de estantería vacía",
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(190.dp)
          .clip(RoundedCornerShape(20.dp))
      )
      Spacer(Modifier.height(20.dp))
      Text(
        text = if (hasAnyBooks) "Sin coincidencias" else "Tu biblioteca está lista",
        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlayfairDisplayFamily)
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = if (hasAnyBooks) {
          "No encontramos libros con los filtros o búsqueda actuales."
        } else {
          "Importa archivos EPUB o PDF desde tu dispositivo para comenzar una lectura tranquila."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(Modifier.height(20.dp))
      Button(
        onClick = if (hasAnyBooks) onClearFilters else onImportClick,
        modifier = Modifier.testTag("empty_state_action_button")
      ) {
        Text(if (hasAnyBooks) "Mostrar todos los libros" else "Importar EPUB o PDF")
      }
    }
  }
}

private fun formatBookMetaSummary(book: BookEntity, wpm: Int): String {
  val unitText = if (book.format == BookFormat.EPUB) {
    "${book.totalUnits} capítulos"
  } else {
    "${book.totalUnits} páginas"
  }
  val rem = LocalBookIntelligence.estimateRemainingMinutes(
    book.totalWordCount, book.totalUnits, book.progressFraction, wpm
  )
  val kb = (book.fileSizeBytes / 1024L).coerceAtLeast(1L)
  val sizeText = if (kb >= 1024L) String.format("%.1f MB", kb / 1024f) else "$kb KB"
  return "$unitText · $sizeText · ~$rem min rest."
}
