package ai.xrav.xravscan.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.xrav.xravscan.domain.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.repository.ProviderRepository
import ai.xrav.xravscan.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val providers: Int = 0,
    val cidrLoaded: Int = 0,
    val results: Int = 0,
    val discoveries: Int = 0,
    val running: Boolean = false,
    val log: List<String> = emptyList(),
)

private data class DbCounts(
    val providers: Int,
    val cidr: Int,
    val results: Int,
    val discoveries: Int,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val scanRepository: ScanRepository,
    private val discoveryRepository: DiscoveryRepository,
) : ViewModel() {

    private val running = MutableStateFlow(false)
    private val log = MutableStateFlow<List<String>>(emptyList())

    private val counts = combine(
        providerRepo.observeAll(),
        providerRepo.observeCidrCount(),
        providerRepo.observeResultCount(),
        providerRepo.observeDiscoveryCount(),
    ) { providers, cidr, results, discoveries ->
        DbCounts(providers.size, cidr, results, discoveries)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        counts,
        running,
        log,
    ) { db, isRunning, lines ->
        DashboardUiState(
            providers = db.providers,
            cidrLoaded = db.cidr,
            results = db.results,
            discoveries = db.discoveries,
            running = isRunning,
            log = lines,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun startScan() {
        if (running.value) return
        running.value = true
        appendLog("Quick scan starting…")
        viewModelScope.launch {
            try {
                scanRepository.runQuickScan(sampleSize = 24) { line -> appendLog(line) }
            } catch (t: Throwable) {
                appendLog("scan failed: ${t.message ?: t::class.simpleName}")
            } finally {
                running.value = false
            }
        }
    }

    fun runSmartAppend() {
        if (running.value) return
        running.value = true
        appendLog("Smart Append starting…")
        viewModelScope.launch {
            try {
                discoveryRepository.runSmartAppend { line -> appendLog(line) }
            } catch (t: Throwable) {
                appendLog("smart append failed: ${t.message ?: t::class.simpleName}")
            } finally {
                running.value = false
            }
        }
    }

    private fun appendLog(line: String) {
        log.update { (it + line).takeLast(30) }
    }
}
