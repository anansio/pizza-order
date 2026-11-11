package io.github.kaerbr.pizzaorder

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Generic JSON persistence for filesDir.
 * Replaces both MenuRepo and OrderRepo with shared logic.
 *
 * AtomicFile: a crash or dead battery mid-write leaves the previous file intact
 * instead of a half-written one (which load() would have replaced with the default).
 * Mutex: saves run one at a time in the order they were started, so an older
 * list can never overwrite a newer one.
 */
class JsonStore<T>(
    context: Context,
    fileName: String,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    private val pretty: Boolean = false
) : JsonFile<T> {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = pretty
    }

    override suspend fun load(): T = withContext(Dispatchers.IO) {
        val text = try {
            file.readFully().decodeToString()
        } catch (_: FileNotFoundException) {
            save(defaultValue)
            return@withContext defaultValue
        }
        runCatching { json.decodeFromString(serializer, text) }
            .getOrElse {
                // The next save overwrites the file, so set it aside: it may be all that's left of tonight's orders.
                runCatching { file.baseFile.copyTo(File("${file.baseFile.path}.broken"), overwrite = true) }
                defaultValue
            }
    }

    override suspend fun save(data: T): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            // A full disk mustn't take the app down mid-party: the state stays in memory and the next save tries again.
            try {
                val out = file.startWrite()
                try {
                    out.write(json.encodeToString(serializer, data).toByteArray())
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            } catch (e: IOException) {
                Log.e("JsonStore", "Speichern fehlgeschlagen", e)
            }
        }
    }
}
