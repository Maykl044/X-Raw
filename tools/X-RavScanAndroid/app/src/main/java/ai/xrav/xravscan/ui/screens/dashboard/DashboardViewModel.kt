package ai.xrav.xravscan.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.xrav.xravscan.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val providers: Int = 0,
    val cidrLoaded: Int = 0,
    val results: Int = 0,
    val discoveries: Int = 0,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repo: ProviderRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        repo.observeAll(),
        repo.observeCidrCount(),
        repo.observeResultCount(),
        repo.observeDiscoveryCount(),
    ) { providers, cidrCount, resultCount, discoveryCount ->
        DashboardUiState(
            providers = providers.size,
            cidrLoaded = cidrCount,
            results = resultCount,
            discoveries = discoveryCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )
}
