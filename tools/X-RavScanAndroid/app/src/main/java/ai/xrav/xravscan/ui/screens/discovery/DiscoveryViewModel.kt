package ai.xrav.xravscan.ui.screens.discovery

import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.domain.repository.DiscoveryRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val pending: List<Discovery> = emptyList(),
    val applied: List<Discovery> = emptyList(),
    val log: List<String> = emptyList(),
    val running: Boolean = false,
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val repo: DiscoveryRepository,
) : ViewModel() {

    private val log = MutableStateFlow<List<String>>(emptyList())
    private val running = MutableStateFlow(false)

    val uiState: StateFlow<DiscoveryUiState> = combine(
        repo.observeAll(),
        log.asStateFlow(),
        running.asStateFlow(),
    ) { rows, lines, isRunning ->
        DiscoveryUiState(
            pending = rows.filter { !it.applied },
            applied = rows.filter { it.applied }.take(50),
            log = lines,
            running = isRunning,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DiscoveryUiState(),
    )

    fun runSmartAppend() {
        if (running.value) return
        running.value = true
        appendLog("Smart Append starting…")
        viewModelScope.launch {
            try {
                repo.runSmartAppend(::appendLog)
            } catch (t: Throwable) {
                appendLog("Smart Append failed: ${t.message ?: t::class.simpleName}")
            } finally {
                running.value = false
            }
        }
    }

    fun runCleanAndOptimize() {
        if (running.value) return
        running.value = true
        appendLog("Clean & Optimize starting…")
        viewModelScope.launch {
            try {
                repo.cleanAndOptimize(::appendLog)
            } catch (t: Throwable) {
                appendLog("Clean & Optimize failed: ${t.message ?: t::class.simpleName}")
            } finally {
                running.value = false
            }
        }
    }

    fun applyDiscovery(id: Long) {
        viewModelScope.launch { repo.applyDiscovery(id) }
    }

    fun applyAllForProvider(slug: String) {
        viewModelScope.launch {
            val applied = repo.applyAllForProvider(slug)
            appendLog("$slug: applied $applied range(s)")
        }
    }

    fun dismiss(id: Long) {
        viewModelScope.launch { repo.dismiss(id) }
    }

    fun clearLog() {
        log.value = emptyList()
    }

    private suspend fun appendLog(line: String) {
        log.update { it + line }
    }
}
