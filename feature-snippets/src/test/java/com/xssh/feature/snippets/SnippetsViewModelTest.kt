/*
 * xSSH — SnippetsViewModel unit tests.
 *
 * Scope: verify the VM correctly reflects DAO state through its
 * `state` StateFlow, that `blank()` produces a well-formed skeleton, that
 * `save()` guards against blank labels, and that `delete()` reaches the DAO.
 *
 * We use Dispatchers.setMain(UnconfinedTestDispatcher) so viewModelScope runs
 * on the test thread and every action completes before the assertion. The
 * DAO is a hand-rolled in-memory fake — no mockk needed for a boundary this
 * small.
 */
package com.xssh.feature.snippets

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.xssh.core.data.dao.SnippetDao
import com.xssh.core.data.entity.SnippetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnippetsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `blank produces a well-formed skeleton with a unique id`() {
        val vm = SnippetsViewModel(FakeSnippetDao())
        val a = vm.blank()
        val b = vm.blank()

        assertThat(a.label).isEmpty()
        assertThat(a.body).isEmpty()
        assertThat(a.executeOnPaste).isFalse()
        assertThat(a.id).isNotEmpty()
        assertThat(a.id).isNotEqualTo(b.id) // UUID collisions here would be catastrophic
    }

    @Test fun `save with a blank label is silently dropped`() =
        runTest(dispatcher) {
            val dao = FakeSnippetDao()
            val vm = SnippetsViewModel(dao)

            vm.save(SnippetEntity(id = "x", label = "   ", body = "echo hello"))

            assertThat(dao.snapshot()).isEmpty()
        }

    @Test fun `save with a blank command is silently dropped`() =
        runTest(dispatcher) {
            val dao = FakeSnippetDao()
            val vm = SnippetsViewModel(dao)

            vm.save(SnippetEntity(id = "x", label = "empty", body = "   "))

            assertThat(dao.snapshot()).isEmpty()
        }

    @Test fun `save persists and shows up in state`() =
        runTest(dispatcher) {
            val dao = FakeSnippetDao()
            val vm = SnippetsViewModel(dao)

            vm.state.test {
                assertThat(awaitItem().items).isEmpty()

                vm.save(SnippetEntity(id = "1", label = "list", body = "ls -la"))
                val next = awaitItem()

                assertThat(next.items).hasSize(1)
                assertThat(next.items.first().label).isEqualTo("list")
                assertThat(next.items.first().body).isEqualTo("ls -la")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test fun `save then delete leaves DAO empty`() =
        runTest(dispatcher) {
            val dao = FakeSnippetDao()
            val vm = SnippetsViewModel(dao)
            val snip = SnippetEntity(id = "1", label = "list", body = "ls")

            vm.save(snip)
            vm.delete(snip)

            assertThat(dao.snapshot()).isEmpty()
        }

    @Test fun `state reflects DAO ordering case-insensitive by label`() =
        runTest(dispatcher) {
            val dao = FakeSnippetDao()
            val vm = SnippetsViewModel(dao)

            // Insert deliberately out of order.
            vm.save(SnippetEntity(id = "3", label = "gamma", body = "echo gamma"))
            vm.save(SnippetEntity(id = "1", label = "Alpha", body = "echo alpha"))
            vm.save(SnippetEntity(id = "2", label = "beta", body = "echo beta"))

            vm.state.test {
                val ordered = awaitItem().items.map { it.label }
                assertThat(ordered).containsExactly("Alpha", "beta", "gamma").inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * In-memory DAO fake with case-insensitive ordering by label — matches
     * the Room "ORDER BY label COLLATE NOCASE" clause in [SnippetDao].
     */
    private class FakeSnippetDao : SnippetDao {
        private val backing = MutableStateFlow<Map<String, SnippetEntity>>(emptyMap())

        fun snapshot(): List<SnippetEntity> = backing.value.values.toList()

        override fun observeAll(): Flow<List<SnippetEntity>> =
            backing.map { m -> m.values.sortedBy { it.label.lowercase() } }

        override suspend fun byId(id: String): SnippetEntity? = backing.value[id]

        override suspend fun upsert(entity: SnippetEntity) {
            backing.update { it + (entity.id to entity) }
        }

        override suspend fun delete(entity: SnippetEntity) {
            backing.update { it - entity.id }
        }
    }
}
