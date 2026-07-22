package com.xssh.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            XSshDatabase::class.java,
        )

    @Test
    fun migrate1To2PreservesProfilesAndCreatesPersistentTransferQueue() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO connections (
                    id, name, host, port, username, authKind, encryptedPassword,
                    encryptedPrivateKey, encryptedKeyPassphrase, compression,
                    keepAliveSeconds, connectTimeoutMs, ephemeral, agentForwarding,
                    lastUsedEpochMs, tags
                ) VALUES (
                    'migration-profile', 'Migration test', 'example.test', 22,
                    'tester', 0, NULL, NULL, NULL, 1, 30, 10000, 0, 0, NULL, '[]'
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            MIGRATION_1_2,
        ).use { database ->
            database.query(
                "SELECT name FROM connections WHERE id = 'migration-profile'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Migration test", cursor.getString(0))
            }
            database.query(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'table' AND name = 'sftp_transfer_queue'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "xssh-migration-test"
    }
}
