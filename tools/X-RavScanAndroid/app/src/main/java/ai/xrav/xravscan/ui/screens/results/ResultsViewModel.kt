package ai.xrav.xravscan.ui.screens.results

import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.domain.repository.ScanRepository
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

data class ResultsUiState(
    val results: List<ScanResult> = emptyList(),
    val running: Boolean = false,
    val log: List<String> = emptyList(),
)

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repo: ScanRepository,
) : ViewModel() {

    private val running = MutableStateFlow(false)
    private val log = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<ResultsUiState> = combine(
        repo.observeRecent(),
        running,
        log,
    ) { results, isRunning, lines ->
        ResultsUiState(results = results, running = isRunning, log = lines)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ResultsUiState(),
    )

    fun runQuickScan() {
        if (running.value) return
        running.value = true
        log.update { it + "Quick scan starting…" }
        viewModelScope.launch {
            try {
                repo.runQuickScan(sampleSize = 32) { line ->
                    log.update { it + line }
                }
            } catch (t: Throwable) {
                log.update { it + "scan failed: ${t.message ?: t::class.simpleName}" }
            } finally {
                running.value = false
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clearAll() }
    }

    fun clearLog() {
        log.value = emptyList()
    }
}
