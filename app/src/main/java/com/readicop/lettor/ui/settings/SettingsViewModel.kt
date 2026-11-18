package com.readicop.lettor.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readicop.lettor.data.preferences.AppThemeMode
import com.readicop.lettor.data.preferences.ReaderFont
import com.readicop.lettor.data.preferences.ReaderPreferences
import com.readicop.lettor.data.preferences.ReaderPreferencesRepository
import com.readicop.lettor.data.preferences.ReaderTheme
import com.readicop.lettor.data.repository.LibraryRepository
import com.readicop.lettor.notifications.ReadingReminderWorker
import com.readicop.lettor.notifications.ResumeReadingScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val appContext: Context,
  private val preferencesRepository: ReaderPreferencesRepository,
  private val repository: LibraryRepository
) : ViewModel() {

  val preferences: StateFlow<ReaderPreferences> = preferencesRepository.preferencesFlow
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = ReaderPreferences()
    )

  private val _isBackupBusy = MutableStateFlow(false)
  val isBackupBusy: StateFlow<Boolean> = _isBackupBusy.asStateFlow()

  private val _messages = MutableSharedFlow<String>()
  val messages = _messages.asSharedFlow()

  fun setAppThemeMode(mode: AppThemeMode) {
    viewModelScope.launch { preferencesRepository.setAppThemeMode(mode) }
  }

  fun setReaderTheme(theme: ReaderTheme) {
    viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
  }

  fun setReaderFont(font: ReaderFont) {
    viewModelScope.launch { preferencesRepository.setReaderFont(font) }
  }

  fun setFontSizeSp(size: Float) {
    viewModelScope.launch { preferencesRepository.setFontSizeSp(size) }
  }

  fun setEpubScrollMode(scroll: Boolean) {
    viewModelScope.launch { preferencesRepository.setEpubScrollMode(scroll) }
  }

  fun setPdfScrollMode(scroll: Boolean) {
    viewModelScope.launch { preferencesRepository.setPdfScrollMode(scroll) }
  }

  fun setPdfNightFilter(enabled: Boolean) {
    viewModelScope.launch { preferencesRepository.setPdfNightFilter(enabled) }
  }

  fun setKeepScreenOn(keepOn: Boolean) {
    viewModelScope.launch { preferencesRepository.setKeepScreenOn(keepOn) }
  }

  fun setDailyGoalMinutes(minutes: Int) {
    viewModelScope.launch { preferencesRepository.setDailyGoalMinutes(minutes) }
  }

  fun setReminder(enabled: Boolean, hour: Int, minute: Int) {
    viewModelScope.launch {
      preferencesRepository.setReminderSettings(enabled, hour, minute)
      ReadingReminderWorker.scheduleDailyReminder(appContext, enabled, hour, minute)
      if (enabled) {
        val hh = hour.toString().padStart(2, '0')
        val mm = minute.toString().padStart(2, '0')
        _messages.emit("Recordatorio diario activado a las $hh:$mm")
      } else {
        _messages.emit("Recordatorio diario desactivado")
      }
    }
  }

  fun setResumeReminderEnabled(enabled: Boolean) {
    viewModelScope.launch {
      preferencesRepository.setResumeReminderEnabled(enabled)
      if (!enabled) {
        ResumeReadingScheduler.cancelAll(appContext)
        _messages.emit("Avisos de «Te quedaste leyendo» desactivados")
      } else {
        _messages.emit("Avisos de «Te quedaste leyendo» activados")
      }
    }
  }

  fun setResumeReminderDelayHours(hours: Int) {
    viewModelScope.launch {
      preferencesRepository.setResumeReminderDelayHours(hours)
      _messages.emit("Aviso programado tras $hours horas sin leer (respetando horario)")
    }
  }

  fun exportBackup(uri: Uri) {
    viewModelScope.launch {
      _isBackupBusy.value = true
      repository.exportBackup(uri)
        .onSuccess { count ->
          _messages.emit("Copia de seguridad exportada ($count libros y tus notas)")
        }
        .onFailure { err ->
          _messages.emit(err.localizedMessage ?: "No se pudo exportar la copia de seguridad")
        }
      _isBackupBusy.value = false
    }
  }

  fun importBackup(uri: Uri) {
    viewModelScope.launch {
      _isBackupBusy.value = true
      repository.importBackup(uri)
        .onSuccess { summary ->
          _messages.emit(
            "Restaurado: ${summary.booksRestored} libros, ${summary.annotationsRestored} notas y ${summary.wordsRestored} palabras"
          )
        }
        .onFailure { err ->
          _messages.emit(err.localizedMessage ?: "No se pudo importar la copia de seguridad")
        }
      _isBackupBusy.value = false
    }
  }

  class Factory(
    private val appContext: Context,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val repository: LibraryRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return SettingsViewModel(appContext, preferencesRepository, repository) as T
    }
  }
}
