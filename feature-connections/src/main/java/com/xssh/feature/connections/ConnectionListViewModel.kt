package com.xssh.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xssh.core.ssh.SshConnectionProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionListState(
    val items: List<SshConnectionProfile> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ConnectionListViewModel
    @Inject
    constructor(
        private val repo: ConnectionRepository,
        private val runtimeCoordinator: ConnectionRuntimeCoordinator,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val error = MutableStateFlow<String?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val listStream =
            query.flatMapLatest { q ->
                if (q.isBlank()) repo.observeAll() else repo.search(q.trim())
            }

        val state: StateFlow<ConnectionListState> =
            combine(listStream, query, error) { list, q, failure ->
                ConnectionListState(items = list, query = q, loading = false, error = failure)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionListState())

        fun onQueryChanged(q: String) {
            query.value = q
        }

        fun clearError() {
            error.value = null
        }

        fun delete(id: String) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    error.value = null
                    runtimeCoordinator.stopBeforeDelete(id)
                    repo.delete(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    error.update { t.message ?: "Unable to delete this connection." }
                }
            }
        }
    }
