package com.personal.music.catalog

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class CatalogFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object CatalogParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Throws(CatalogFormatException::class)
    fun parse(jsonText: String): RemoteCatalog {
        return try {
            json.decodeFromString<RemoteCatalog>(jsonText)
        } catch (e: SerializationException) {
            throw CatalogFormatException("Invalid catalog format: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CatalogFormatException("Invalid catalog format: ${e.message}", e)
        }
    }
}
