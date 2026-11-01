package com.knightwarriorsclub.app.data

import android.os.Handler
import android.os.Looper
import com.knightwarriorsclub.app.ClubConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Supabase Realtime over a raw Phoenix-channels WebSocket — the same live
 * pipe the website uses (its supabase-js client speaks this protocol).
 * Carries DM inserts (postgres_changes), chat typing broadcasts and the
 * whole call-signalling flow (broadcast), with heartbeat + auto-reconnect.
 * All callbacks are delivered on the main thread.
 */
class RealtimeClient {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val main = Handler(Looper.getMainLooper())

    private var ws: WebSocket? = null
    private var open = false
    private var closedByUs = false
    private var refCounter = 0
    private var heartbeat: Runnable? = null
    private var reconnect: Runnable? = null

    @Volatile
    var accessToken: String? = null

    private inner class Channel(
        val name: String,
        val changesTable: String?,
        val onBroadcast: ((String, JsonObject) -> Unit)?,
        val onInsert: ((JsonObject) -> Unit)?,
        val onJoined: (() -> Unit)?
    ) {
        val topic: String = "realtime:$name"
        var joined: Boolean = false
        var bindingId: Long = -1
    }

    private val channels = LinkedHashMap<String, Channel>()

    fun connect(token: String?) {
        accessToken = token
        closedByUs = false
        if (ws != null) return
        openSocket()
    }

    fun disconnect() {
        closedByUs = true
        heartbeat?.let { main.removeCallbacks(it) }
        reconnect?.let { main.removeCallbacks(it) }
        channels.clear()
        ws?.close(1000, "bye")
        ws = null
        open = false
    }

    private fun openSocket() {
        val url = ClubConfig.SUPABASE_URL
            .replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=" + ClubConfig.SUPABASE_PUBLISHABLE_KEY + "&vsn=1.0.0"
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                startHeartbeat()
                channels.values.forEach { sendJoin(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketGone()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketGone()
            }
        })
    }

    private fun socketGone() {
        if (ws != null) {
            ws = null
            open = false
            channels.values.forEach { it.joined = false; it.bindingId = -1 }
        }
        heartbeat?.let { main.removeCallbacks(it) }
        if (!closedByUs) {
            reconnect?.let { main.removeCallbacks(it) }
            val r = Runnable {
                if (!closedByUs && ws == null) openSocket()
            }
            reconnect = r
            main.postDelayed(r, 2000)
        }
    }

    private fun startHeartbeat() {
        heartbeat?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                send(
                    buildJsonObject {
                        put("topic", "phoenix")
                        put("event", "heartbeat")
                        put("payload", buildJsonObject {})
                        put("ref", nextRef())
                    }
                )
                main.postDelayed(this, 25000)
            }
        }
        heartbeat = r
        main.postDelayed(r, 25000)
    }

    private fun nextRef(): String {
        refCounter += 1
        return refCounter.toString()
    }

    private fun send(obj: JsonObject) {
        val socket = ws ?: return
        if (!open) return
        runCatching { socket.send(obj.toString()) }
    }

    // ── channels ─────────────────────────────────────────────────────────

    /** Join a broadcast-only channel (call signalling, typing). */
    fun joinBroadcast(
        name: String,
        onEvent: (String, JsonObject) -> Unit,
        onJoined: (() -> Unit)? = null
    ) {
        if (channels.containsKey(name)) {
            val ch = channels[name]!!
            if (!ch.joined && open) sendJoin(ch)
            else onJoined?.invoke()
            return
        }
        val ch = Channel(name, null, onEvent, null, onJoined)
        channels[name] = ch
        if (open) sendJoin(ch)
    }

    /** Join a channel watching INSERTs on one public table (live DMs). */
    fun joinInserts(
        name: String,
        table: String,
        onInsert: (JsonObject) -> Unit
    ) {
        if (channels.containsKey(name)) return
        val ch = Channel(name, table, null, onInsert, null)
        channels[name] = ch
        if (open) sendJoin(ch)
    }

    fun leave(name: String) {
        val ch = channels.remove(name) ?: return
        if (open) {
            send(
                buildJsonObject {
                    put("topic", ch.topic)
                    put("event", "phx_leave")
                    put("payload", buildJsonObject {})
                    put("ref", nextRef())
                    put("join_ref", nextRef())
                }
            )
        }
    }

    private fun sendJoin(ch: Channel) {
        val ref = nextRef()
        val payload = buildJsonObject {
            put(
                "config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("ack", false)
                        put("self", false)
                    })
                    put("presence", buildJsonObject { put("key", "") })
                    if (ch.changesTable != null) {
                        put("postgres_changes", buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("event", "INSERT")
                                    put("schema", "public")
                                    put("table", ch.changesTable)
                                }
                            )
                        })
                    } else {
                        put("postgres_changes", buildJsonArray {})
                    }
                }
            )
            accessToken?.let { put("access_token", it) }
        }
        send(
            buildJsonObject {
                put("topic", ch.topic)
                put("event", "phx_join")
                put("payload", payload)
                put("ref", ref)
                put("join_ref", ref)
            }
        )
    }

    /** Send a broadcast on an already-joined channel. */
    fun broadcast(name: String, event: String, payload: JsonObject) {
        val ch = channels[name] ?: return
        if (!ch.joined) return
        send(
            buildJsonObject {
                put("topic", ch.topic)
                put("event", "broadcast")
                put(
                    "payload", buildJsonObject {
                        put("type", "broadcast")
                        put("event", event)
                        put("payload", payload)
                    }
                )
                put("ref", nextRef())
            }
        )
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val topic = msg["topic"]?.jsonPrimitive?.contentOrNull ?: return
        val event = msg["event"]?.jsonPrimitive?.contentOrNull ?: return
        val payload = msg["payload"] as? JsonObject ?: buildJsonObject {}
        val ch = channels.values.firstOrNull { it.topic == topic } ?: return
        when (event) {
            "phx_reply" -> {
                val status = payload["status"]?.jsonPrimitive?.contentOrNull
                if (status == "ok" && !ch.joined) {
                    ch.joined = true
                    val bindings = payload["response"]?.jsonObject
                        ?.get("postgres_changes") as? JsonArray
                    val first = bindings?.firstOrNull() as? JsonObject
                    ch.bindingId = first?.get("id")?.jsonPrimitive?.contentOrNull
                        ?.toLongOrNull() ?: -1
                    val cb = ch.onJoined
                    if (cb != null) main.post { cb() }
                }
            }
            "broadcast" -> {
                val innerEvent = payload["event"]?.jsonPrimitive?.contentOrNull ?: return
                val innerPayload = payload["payload"] as? JsonObject ?: buildJsonObject {}
                val cb = ch.onBroadcast ?: return
                main.post { cb(innerEvent, innerPayload) }
            }
            "postgres_changes" -> {
                val cb = ch.onInsert ?: return
                val ids = payload["ids"] as? JsonArray
                val mine = ids?.any {
                    it.jsonPrimitive.contentOrNull?.toLongOrNull() == ch.bindingId
                } ?: true
                if (!mine) return
                val data = payload["data"] as? JsonObject ?: return
                if (data["type"]?.jsonPrimitive?.contentOrNull != "INSERT") return
                val record = data["record"] as? JsonObject ?: return
                main.post { cb(record) }
            }
            "phx_error", "phx_close" -> {
                ch.joined = false
                ch.bindingId = -1
                if (open) sendJoin(ch)
            }
        }
    }
}
