package ai.xrav.xravscan.ui.screens.settings

import ai.xrav.xravscan.data.export.Exporter
import ai.xrav.xravscan.domain.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.repository.ScanRepository
import ai.xrav.xravscan.ui.localization.LocalizationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val languageTag: String? = null,
    val log: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val localizationManager: LocalizationManager,
    private val exporter: Exporter,
    private val scanRepository: ScanRepository,
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {

    private val log = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<SettingsUiState> = combine(
        localizationManager.state,
        log,
    ) { locale, log ->
        SettingsUiState(languageTag = locale.tag, log = log)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(languageTag = localizationManager.currentTag()),
    )

    fun pickLanguage(tag: String?) {
        localizationManager.setLanguage(tag)
    }

    fun exportHosts() {
        viewModelScope.launch {
            appendLog("Exporting reachable hosts…")
            describe(exporter.exportHostsTxt())
        }
    }

    fun exportRanges() {
        viewModelScope.launch {
            appendLog("Exporting CIDR ranges…")
            describe(exporter.exportRangesJson())
        }
    }

    fun clearScanResults() {
        viewModelScope.launch {
            scanRepository.clearAll()
            appendLog("Scan results cleared")
        }
    }

    fun clearPendingDiscoveries() {
        viewModelScope.launch {
            discoveryRepository.dismissAllPending()
            appendLog("Pending discoveries cleared")
        }
    }

    private fun describe(outcome: Exporter.ExportOutcome) {
        val line = when (outcome) {
            is Exporter.ExportOutcome.Saved ->
                "Saved ${outcome.name} → ${outcome.location}"
            Exporter.ExportOutcome.Empty ->
                "Nothing to export — table is empty"
            is Exporter.ExportOutcome.Failure ->
                "Export failed: ${outcome.message}"
        }
        appendLog(line)
    }

    private fun appendLog(line: String) {
        log.update { (it + line).takeLast(20) }
    }
}
