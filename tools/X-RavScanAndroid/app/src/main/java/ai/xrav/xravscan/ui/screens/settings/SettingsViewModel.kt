package ai.xrav.xravscan.ui.screens.settings

import ai.xrav.xravscan.data.export.Exporter
import ai.xrav.xravscan.domain.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.repository.ScanRepository
import ai.xrav.xravscan.util.LocaleManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val languageTag: String? = null,
    val log: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val localeManager: LocaleManager,
    private val exporter: Exporter,
    private val scanRepository: ScanRepository,
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(languageTag = localeManager.currentTag()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun pickLanguage(tag: String?) {
        localeManager.setLanguage(tag)
        _state.update { it.copy(languageTag = tag) }
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
        _state.update { it.copy(log = (it.log + line).takeLast(20)) }
    }
}
