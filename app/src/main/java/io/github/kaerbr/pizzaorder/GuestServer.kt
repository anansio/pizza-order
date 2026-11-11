package io.github.kaerbr.pizzaorder

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * NanoHTTPD as the transport and [GuestApi] as the server: this class only turns a session into a
 * [GuestRequest] and the answer back into a response, so every route stays testable without a socket.
 *
 * NanoHTTPD runs one thread per request. Blocking that thread on the coroutine is fine — the desk answers
 * in milliseconds and printing is launched beside it, so a slow Bluetooth printer never holds up a phone.
 */
class GuestServer(port: Int, private val api: GuestApi) : NanoHTTPD(port) {

    private val runner = BoundedRunner(THREADS)

    init {
        setAsyncRunner(runner)
    }

    override fun serve(session: IHTTPSession): Response {
        val query = session.parameters ?: emptyMap()
        val remoteIp = session.remoteIpAddress ?: ""

        // Rate limit and join code before the body: a stranger on the same WiFi lacks the code, so it must
        // not buy them a body read and a JSON parse. This is the only place [GuestApi.handle] is called, so
        // the order is the one promised: admit, then read.
        val refusal = api.admit(remoteIp, query)
        if (refusal != null) {
            discardBody(session)
            return answer(refusal)
        }

        val request = GuestRequest(
            method = session.method?.name ?: "GET",
            path = session.uri ?: "/",
            remoteIp = remoteIp,
            query = query,
            headers = session.headers ?: emptyMap(),
            body = readBody(session) ?: return oversized(),
        )

        val response = try {
            runBlocking { api.handle(request) }
        } catch (e: Exception) {
            return text(Response.Status.INTERNAL_ERROR, "Interner Fehler.")
        }
        return answer(response)
    }

    override fun stop() {
        super.stop()
        runner.shutdown()
    }

    private fun answer(response: GuestResponse): Response =
        newFixedLengthResponse(
            Response.Status.lookup(response.status) ?: Response.Status.INTERNAL_ERROR,
            response.contentType,
            response.body,
        ).apply {
            // Nothing here is worth caching: the menu changes and the status page polls.
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            // No keep-alive: with a fixed pool, an idle kept-alive connection would hold its thread until the
            // socket timeout and starve the other phones. It also keeps a refused body from being parsed as
            // the next request on the same connection.
            closeConnection(true)
        }

    /**
     * Reads exactly content-length bytes and decodes them as UTF-8, rather than letting NanoHTTPD's
     * parseBody() do it: that falls back to US-ASCII when the client sends no charset, which turns every
     * umlaut in a guest's name into "?". Null means the body is too large to be a guest order.
     */
    private fun readBody(session: IHTTPSession): String? {
        val length = contentLength(session)
        if (length <= 0) return ""
        if (length > GuestApi.MAX_BODY_BYTES) return null

        val bytes = ByteArray(length)
        var read = 0
        val input = session.inputStream
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, StandardCharsets.UTF_8)
    }

    /**
     * Swallows what already arrived of a refused body, so closing the socket doesn't turn the answer into a
     * reset. Only the bytes present are read — waiting for a body nobody sent would hold the 403 back — and
     * the amount is capped: what an unauthenticated request costs is a read, never a buffer.
     */
    private fun discardBody(session: IHTTPSession) {
        val input = session.inputStream
        var left = contentLength(session).coerceIn(0, DRAIN_LIMIT)
        while (left > 0 && runCatching { input.available() > 0 }.getOrDefault(false)) {
            val n = runCatching { input.read(ByteArray(minOf(1024, left))) }.getOrDefault(-1)
            if (n < 0) return
            left -= n
        }
    }

    private fun contentLength(session: IHTTPSession) = session.headers["content-length"]?.toIntOrNull() ?: 0

    private fun oversized(): Response =
        text(Response.Status.PAYLOAD_TOO_LARGE, "Anfrage zu groß.").apply { addHeader("Connection", "close") }

    private fun text(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, GuestResponse.PLAIN_TYPE, body)

    /**
     * NanoHTTPD's default runner starts an unbounded thread per connection: a device on the LAN could park
     * threads (and their buffers) without ever knowing the join code. A fixed pool with a small queue caps
     * that; connections past it are dropped instead of piling up in the process.
     */
    private class BoundedRunner(threads: Int) : AsyncRunner {
        private val running = Collections.synchronizedList(mutableListOf<ClientHandler>())
        private val executor = ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(threads * 2),
            { r -> Thread(r, "guest-http").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

        override fun exec(clientHandler: ClientHandler) {
            running.add(clientHandler)
            try {
                executor.execute(clientHandler)
            } catch (e: RejectedExecutionException) {
                running.remove(clientHandler)
                clientHandler.close()
            }
        }

        override fun closeAll() {
            synchronized(running) { running.toList() }.forEach { it.close() }
        }

        override fun closed(clientHandler: ClientHandler) {
            running.remove(clientHandler)
        }

        fun shutdown() {
            closeAll()
            executor.shutdownNow()
        }
    }

    private companion object {
        /** A party has a dozen phones; more parallel requests than this are not a party. */
        const val THREADS = 16

        /** Any guest body fits; a refused request bigger than this is cut off with the connection. */
        const val DRAIN_LIMIT = 64 * 1024
    }
}
