/*
 * xSSH — Snippets ViewModel: Room-backed CRUD list.
 *
 * Snippets are labelled command strings that the user can paste into any
 * active terminal. Storage lives in :core-data; a future SessionScreen wiring
 * will surface a picker that pastes body (+ optional newline) into the PTY.
 */
package com.xssh.feature.snippets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xssh.core.data.dao.SnippetDao
import com.xssh.core.data.entity.SnippetEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SnippetsUiState(
    val items: List<SnippetEntity> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class SnippetsViewModel
    @Inject
    constructor(
        private val dao: SnippetDao,
    ) : ViewModel() {
        val state: StateFlow<SnippetsUiState> =
            dao.observeAll()
                .map { SnippetsUiState(items = it, loading = false) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SnippetsUiState())

        fun blank(): SnippetEntity =
            SnippetEntity(
                id = UUID.randomUUID().toString(),
                label = "",
                body = "",
            )

        fun save(snippet: SnippetEntity) {
            val invalidSnippet =
                listOf(
                    snippet.label.isBlank(),
                    snippet.label.length > 256,
                    snippet.label.any(Char::isISOControl),
                    snippet.body.isBlank(),
                    snippet.body.length > MAX_SNIPPET_BODY_CHARS,
                    '\u0000' in snippet.body,
                    snippet.tags.size > 100,
                    snippet.tags.any { tag -> tag.length > 128 || tag.any(Char::isISOControl) },
                ).any { it }
            if (invalidSnippet) {
                return
            }
            viewModelScope.launch { dao.upsert(snippet) }
        }

        fun delete(snippet: SnippetEntity) {
            viewModelScope.launch { dao.delete(snippet) }
        }

        private companion object {
            const val MAX_SNIPPET_BODY_CHARS = 256 * 1024
        }
    }
