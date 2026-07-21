package com.xssh.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A named command snippet available across connections. */
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey val id: String,
    val label: String,
    val body: String,
    val tags: List<String> = emptyList(),
    /** Append newline after paste and run immediately. */
    val executeOnPaste: Boolean = false,
)
