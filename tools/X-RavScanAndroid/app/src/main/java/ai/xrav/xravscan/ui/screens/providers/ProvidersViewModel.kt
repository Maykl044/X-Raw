package ai.xrav.xravscan.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.xrav.xravscan.domain.model.Provider
import ai.xrav.xravscan.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProvidersUiState(
    val providers: List<Provider> = emptyList(),
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repo: ProviderRepository,
) : ViewModel() {

    val uiState: StateFlow<ProvidersUiState> = repo
        .observeAll()
        .map { ProvidersUiState(providers = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProvidersUiState(),
        )

    fun toggle(slug: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(slug, enabled) }
    }
}
