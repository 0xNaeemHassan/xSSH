package com.xssh.core.data

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json

    @TypeConverter
    fun fromStringList(list: List<String>?): String = JSON_PREFIX + json.encodeToString(list.orEmpty())

    @TypeConverter
    fun toStringList(s: String?): List<String> =
        when {
            s.isNullOrEmpty() -> emptyList()
            s.startsWith(JSON_PREFIX) -> json.decodeFromString(s.removePrefix(JSON_PREFIX))
            else -> s.split(LEGACY_SEPARATOR)
        }

    private companion object {
        const val JSON_PREFIX = "\u0002xssh-tags-v1\u0002"
        const val LEGACY_SEPARATOR = "\u0001"
    }
}
