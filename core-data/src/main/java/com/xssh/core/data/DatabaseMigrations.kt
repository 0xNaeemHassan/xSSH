package com.xssh.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sftp_transfer_queue` (
                    `id` TEXT NOT NULL,
                    `connectionId` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `direction` TEXT NOT NULL,
                    `remotePath` TEXT NOT NULL,
                    `localUri` TEXT NOT NULL,
                    `totalBytes` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `bytesTransferred` INTEGER NOT NULL,
                    `error` TEXT,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`connectionId`) REFERENCES `connections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sftp_transfer_queue_connectionId_createdAtEpochMs` " +
                    "ON `sftp_transfer_queue` (`connectionId`, `createdAtEpochMs`)",
            )
        }
    }
