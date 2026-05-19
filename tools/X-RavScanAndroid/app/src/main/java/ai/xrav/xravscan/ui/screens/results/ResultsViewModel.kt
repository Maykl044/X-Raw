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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val results: List<ScanResult> = emptyList(),
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val fullScanProgress: FullScanProgress? = null,
    /** Provider slug → persisted pause cursor, populated by [refreshPausedScans]. */
    val pausedScans: Map<String, ScanRepository.PausedScan> = emptyMap(),
)

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repo: ScanRepository,
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    private val running = MutableStateFlow(false)
    private val log = MutableStateFlow<List<String>>(emptyList())
    private val fullScanProgress = MutableStateFlow<FullScanProgress?>(null)
    private val pausedScans = MutableStateFlow<Map<String, ScanRepository.PausedScan>>(emptyMap())
    private var fullScanJob: Job? = null

    init { refreshPausedScans() }

    val uiState: StateFlow<ResultsUiState> = combine(
        listOf(
            repo.observeRecent(),
            running,
            log,
            providerRepository.observeAll(),
            fullScanProgress,
            pausedScans,
        ),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ResultsUiState(
            results = values[0] as List<ScanResult>,
            running = values[1] as Boolean,
            log = values[2] as List<String>,
            providers = values[3] as List<Provider>,
            fullScanProgress = values[4] as FullScanProgress?,
            pausedScans = values[5] as Map<String, ScanRepository.PausedScan>,
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

    fun runFullProviderScan(
        providerSlug: String,
        resume: Boolean = false,
    ) {
        if (fullScanJob?.isActive == true) return
        fullScanProgress.value = null
        fullScanJob = viewModelScope.launch {
            try {
                // Phase J — no maxIps argument. The repository default is
                // Long.MAX_VALUE so the iterator runs every IP in every
                // CIDR until exhausted (e.g. all ~6.6M Cloudflare hosts).
                repo.runFullProviderScan(
                    providerSlug = providerSlug,
                    concurrency = 64,
                    resume = resume,
                ).conflate().collect { progress ->
                    fullScanProgress.value = progress
                    progress.message?.let { line -> log.update { it + line } }
                }
            } catch (t: Throwable) {
                log.update { it + "Full scan failed: ${t.message ?: t::class.simpleName}" }
                fullScanProgress.update { current ->
                    current?.copy(done = true, message = "Full scan failed: ${t.message}")
                }
            } finally {
                refreshPausedScans()
            }
        }
    }

    /**
     * Phase I — pause cancels the producer / worker pool but keeps the
     * persisted cursor so the user can Resume from the exact bit-offset.
     */
    fun pauseFullScan() {
        fullScanJob?.cancel()
        fullScanProgress.update { current ->
            current?.copy(done = true, cancelled = true)
        }
        viewModelScope.launch { refreshPausedScans() }
    }

    fun cancelFullScan() {
        // "Cancel" still pauses (so progress isn't lost) — the user can
        // explicitly Discard from the resume card if they want to throw
        // the cursor away.
        pauseFullScan()
    }

    fun discardPausedScan(providerSlug: String) {
        viewModelScope.launch {
            repo.discardPausedScan(providerSlug)
            refreshPausedScans()
        }
    }

    fun refreshPausedScans() {
        viewModelScope.launch {
            val snapshot = mutableMapOf<String, ScanRepository.PausedScan>()
            val providers = runCatching {
                providerRepository.observeAll().first()
            }.getOrDefault(emptyList())
            for (p in providers) {
                val paused = repo.pausedScanFor(p.slug) ?: continue
                snapshot[p.slug] = paused
            }
            pausedScans.value = snapshot
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
