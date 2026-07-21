/*
 * xSSH — TunnelRepository unit tests.
 *
 * The repository is a thin translator between the Room [TunnelEntity] row and
 * the domain [Tunnel] value type from :core-ssh. There is no I/O, so we test
 * with a hand-rolled in-memory DAO fake and assert exact-field equality on the
 * round-trip (record → entity → record).
 *
 * Why a fake DAO instead of mockk: mockk would let us assert call sequences,
 * but a plain fake documents the DAO contract (Flow, sorted by bindPort,
 * upsert semantics) more clearly than argument-captor gymnastics — and a
 * mapper bug will surface as a Truth assertion failure either way.
 */
package com.xssh.feature.tunnels

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.xssh.core.data.dao.TunnelDao
import com.xssh.core.data.entity.TunnelEntity
import com.xssh.core.ssh.Tunnel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TunnelRepositoryTest {
    @Test fun `LOCAL record round-trips through the repo without drift`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            val record =
                TunnelRecord(
                    tunnel =
                        Tunnel(
                            id = "t1",
                            connectionId = "c1",
                            kind = Tunnel.Kind.LOCAL,
                            bindHost = "127.0.0.1",
                            bindPort = 8080,
                            destHost = "internal.example.com",
                            destPort = 80,
                            autoStart = true,
                        ),
                    label = "corporate intranet",
                )

            repo.upsert(record)

            assertThat(repo.byId("t1")).isEqualTo(record)
        }

    @Test fun `DYNAMIC record round-trips with null destination fields`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            val record =
                TunnelRecord(
                    tunnel =
                        Tunnel(
                            id = "socks",
                            connectionId = "c1",
                            kind = Tunnel.Kind.DYNAMIC,
                            bindHost = "127.0.0.1",
                            bindPort = 1080,
                            destHost = null,
                            destPort = null,
                            autoStart = false,
                        ),
                    label = "SOCKS5",
                )

            repo.upsert(record)
            val fetched = repo.byId("socks")

            assertThat(fetched).isEqualTo(record)
            assertThat(fetched?.tunnel?.destHost).isNull()
            assertThat(fetched?.tunnel?.destPort).isNull()
        }

    @Test fun `REMOTE record round-trips`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            val record =
                TunnelRecord(
                    tunnel =
                        Tunnel(
                            id = "rev",
                            connectionId = "c1",
                            kind = Tunnel.Kind.REMOTE,
                            bindHost = "0.0.0.0",
                            bindPort = 2222,
                            destHost = "localhost",
                            destPort = 22,
                            autoStart = false,
                        ),
                    label = "reverse tunnel",
                )

            repo.upsert(record)

            assertThat(repo.byId("rev")).isEqualTo(record)
        }

    @Test fun `observeAll emits inserted rows`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            val record =
                TunnelRecord(
                    tunnel =
                        Tunnel(
                            id = "t",
                            connectionId = "c",
                            kind = Tunnel.Kind.LOCAL,
                            bindPort = 9090,
                            destHost = "x",
                            destPort = 80,
                        ),
                    label = "",
                )
            repo.upsert(record)

            assertThat(repo.observeAll().first()).containsExactly(record)
        }

    @Test fun `observeAll updates when a row is added later`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            repo.observeAll().test {
                assertThat(awaitItem()).isEmpty()

                repo.upsert(
                    TunnelRecord(
                        tunnel =
                            Tunnel(
                                id = "a",
                                connectionId = "c",
                                kind = Tunnel.Kind.LOCAL,
                                bindPort = 9000,
                                destHost = "x",
                                destPort = 80,
                            ),
                        label = "later",
                    ),
                )

                val next = awaitItem()
                assertThat(next).hasSize(1)
                assertThat(next.first().tunnel.id).isEqualTo("a")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test fun `delete removes the row`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            val record =
                TunnelRecord(
                    tunnel =
                        Tunnel(
                            id = "t",
                            connectionId = "c",
                            kind = Tunnel.Kind.LOCAL,
                            bindPort = 9090,
                            destHost = "x",
                            destPort = 80,
                        ),
                    label = "",
                )
            repo.upsert(record)
            repo.delete(record)

            assertThat(repo.byId("t")).isNull()
        }

    @Test fun `observeForConnection filters by connectionId`() =
        runTest {
            val repo = TunnelRepository(FakeTunnelDao())

            repo.upsert(recordFor("a", connection = "conn-a", port = 100))
            repo.upsert(recordFor("b", connection = "conn-b", port = 200))
            repo.upsert(recordFor("c", connection = "conn-a", port = 300))

            val onlyA = repo.observeForConnection("conn-a").first()
            assertThat(onlyA.map { it.tunnel.id }).containsExactly("a", "c").inOrder()
        }

    // -- helpers --------------------------------------------------------------

    private fun recordFor(
        id: String,
        connection: String,
        port: Int,
    ) = TunnelRecord(
        tunnel =
            Tunnel(
                id = id,
                connectionId = connection,
                kind = Tunnel.Kind.LOCAL,
                bindPort = port,
                destHost = "x",
                destPort = 80,
            ),
        label = "",
    )

    /**
     * In-memory DAO fake. Emits sorted-by-bindPort snapshots identically to
     * the Room-generated implementation. State lives in a MutableStateFlow so
     * observers receive fresh values on every upsert/delete without any
     * manual "invalidate" plumbing.
     */
    private class FakeTunnelDao : TunnelDao {
        private val backing = MutableStateFlow<Map<String, TunnelEntity>>(emptyMap())

        override fun observeAll(): Flow<List<TunnelEntity>> = backing.map { m -> m.values.sortedBy { it.bindPort } }

        override fun observeForConnection(id: String): Flow<List<TunnelEntity>> =
            backing.map { m -> m.values.filter { it.connectionId == id }.sortedBy { it.bindPort } }

        override suspend fun listForConnection(id: String): List<TunnelEntity> =
            backing.value.values.filter { it.connectionId == id }.sortedBy { it.bindPort }

        override suspend fun byId(id: String): TunnelEntity? = backing.value[id]

        override suspend fun upsert(entity: TunnelEntity) {
            backing.update { it + (entity.id to entity) }
        }

        override suspend fun delete(entity: TunnelEntity) {
            backing.update { it - entity.id }
        }
    }
}
