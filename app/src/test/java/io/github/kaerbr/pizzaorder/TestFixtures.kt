package io.github.kaerbr.pizzaorder

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [JsonStore] without Android: same JSON, same "keep the last good file" idea, plain java.io.File.
 *
 * Shared by the tests that need a real desk: the service, the HTTP server and the menu all talk files.
 */
class FileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
) : JsonFile<T> {
    // Same settings as JsonStore, or a test would pass on files the app never writes.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun load(): T =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { defaultValue }
        else defaultValue

    override suspend fun save(data: T) {
        file.parentFile?.mkdirs()
        // Write-then-rename, like the app's AtomicFile: a test that reads while a print's save is in flight
        // sees the previous file, never half of the next one.
        val next = File(file.parentFile, "${file.name}.tmp")
        next.writeText(json.encodeToString(serializer, data))
        if (!next.renameTo(file)) {
            next.copyTo(file, overwrite = true)
            next.delete()
        }
    }
}

/** Counts printouts instead of printing them; Bluetooth is not what these tests are about. */
class RecordingPrinter : PrinterPort {
    val markups = mutableListOf<String>()
    override suspend fun print(markup: String): Result<Unit> {
        markups += markup
        return Result.success(Unit)
    }
}
