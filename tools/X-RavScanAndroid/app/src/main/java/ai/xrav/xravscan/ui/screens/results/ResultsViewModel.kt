package ai.xrav.xravscan.ui.screens.results

import ai.xrav.xravscan.domain.model.FullScanProgress
import ai.xrav.xravscan.domain.model.Provider
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.domain.repository.ProviderRepository
import ai.xrav.xravscan.domain.repository.ScanRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val results: List<ScanResult> = emptyList(),
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val fullScanProgress: FullScanProgress? = null,
)

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repo: ScanRepository,
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    private val running = MutableStateFlow(false)
    private val log = MutableStateFlow<List<String>>(emptyList())
    private val fullScanProgress = MutableStateFlow<FullScanProgress?>(null)
    private var fullScanJob: Job? = null

    val uiState: StateFlow<ResultsUiState> = combine(
        repo.observeRecent(),
        running,
        log,
        providerRepository.observeAll(),
        fullScanProgress,
    ) { results, isRunning, lines, providers, progress ->
        ResultsUiState(
            results = results,
            running = isRunning,
            log = lines,
            providers = providers,
            fullScanProgress = progress,
        )
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

    fun runFullProviderScan(providerSlug: String, maxIps: Long = 50_000L) {
        if (fullScanJob?.isActive == true) return
        fullScanProgress.value = null
        fullScanJob = viewModelScope.launch {
            try {
                repo.runFullProviderScan(
                    providerSlug = providerSlug,
                    maxIps = maxIps,
                    concurrency = 64,
                ).conflate().collect { progress ->
                    fullScanProgress.value = progress
                    progress.message?.let { line -> log.update { it + line } }
                }
            } catch (t: Throwable) {
                log.update { it + "Full scan failed: ${t.message ?: t::class.simpleName}" }
                fullScanProgress.update { current ->
                    current?.copy(done = true, message = "Full scan failed: ${t.message}")
                }
            }
        }
    }

    fun cancelFullScan() {
        fullScanJob?.cancel()
        fullScanProgress.update { current ->
            current?.copy(done = true, cancelled = true, message = "Cancelled by user")
        }
    }

    fun dismissFullScanProgress() {
        if (fullScanProgress.value?.done == true) {
            fullScanProgress.value = null
        }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clearAll() }
    }

    fun clearLog() {
        log.value = emptyList()
    }
}
