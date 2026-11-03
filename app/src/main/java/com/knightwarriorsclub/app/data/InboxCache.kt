package com.knightwarriorsclub.app.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Messenger-style local cache (beta37, Pranto 2026-11-03): the inbox
 * list and open chat threads render instantly from the phone's own
 * copy, then refresh quietly from Supabase in the background — the
 * same trick Messenger uses, instead of a "Loading…" spinner on
 * every open. Everything here lives ONLY on this device.
 */
object InboxCache {
    private const val PREFS = "kwc_inbox_cache"
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val members: List<Member>,
        val latest: Map<Long, String>,
        val unread: Map<Long, Int>,
        val blocked: Set<Long>,
        val squads: List<Squad>,
        val squadLatest: Map<Long, Pair<String?, String>>
    )

    private fun JsonObject.s(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun memberToJson(m: Member): JsonObject = buildJsonObject {
        put("id", m.id)
        put("full_name", m.fullName)
        put("username", m.username?.let { JsonPrimitive(it) } ?: JsonNull)
        put("avatar_url", m.avatarUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("role", m.role)
        put("bio", m.bio?.let { JsonPrimitive(it) } ?: JsonNull)
        put("favourite_game", m.favouriteGame?.let { JsonPrimitive(it) } ?: JsonNull)
        put("created_at", m.createdAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cover_url", m.coverUrl?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun memberFromJson(o: JsonObject): Member? = runCatching {
        Member(
            id = o["id"]!!.jsonPrimitive.long,
            fullName = o.s("full_name") ?: "Member",
            username = o.s("username"),
            avatarUrl = o.s("avatar_url"),
            role = o.s("role") ?: "Member",
            bio = o.s("bio"),
            favouriteGame = o.s("favourite_game"),
            createdAt = o.s("created_at"),
            coverUrl = o.s("cover_url")
        )
    }.getOrNull()

    private fun squadToJson(s: Squad): JsonObject = buildJsonObject {
        put("id", s.id)
        put("name", s.name)
        put("game", s.game?.let { JsonPrimitive(it) } ?: JsonNull)
        put("captain_id", s.captainId)
        put("created_at", s.createdAt)
        put("avatar_url", s.avatarUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("motto", s.motto?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun squadFromJson(o: JsonObject): Squad? = runCatching {
        Squad(
            id = o["id"]!!.jsonPrimitive.long,
            name = o.s("name") ?: "Squad",
            game = o.s("game"),
            captainId = o["captain_id"]!!.jsonPrimitive.long,
            createdAt = o.s("created_at") ?: "",
            avatarUrl = o.s("avatar_url"),
            motto = o.s("motto")
        )
    }.getOrNull()

    fun saveInbox(
        context: Context, meId: Long,
        members: List<Member>,
        latest: Map<Long, String>,
        unread: Map<Long, Int>,
        blocked: Set<Long>,
        squads: List<Squad>,
        squadLatest: Map<Long, SquadMsg?>
    ) {
        runCatching {
            val root = buildJsonObject {
                put("members", buildJsonArray { members.forEach { add(memberToJson(it)) } })
                put("latest", buildJsonObject {
                    latest.forEach { (k, v) -> put(k.toString(), v) }
                })
                put("unread", buildJsonObject {
                    unread.forEach { (k, v) -> put(k.toString(), v) }
                })
                put("blocked", buildJsonArray { blocked.forEach { add(JsonPrimitive(it)) } })
                put("squads", buildJsonArray { squads.forEach { add(squadToJson(it)) } })
                put("squad_latest", buildJsonObject {
                    squadLatest.forEach { (k, v) ->
                        put(k.toString(), buildJsonObject {
                            put("body", v?.body?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("created_at", v?.createdAt ?: "")
                        })
                    }
                })
            }
            prefs(context).edit().putString("inbox_$meId", root.toString()).apply()
        }
    }

    fun loadInbox(context: Context, meId: Long): Snapshot? = runCatching {
        val raw = prefs(context).getString("inbox_$meId", null) ?: return null
        val root = json.parseToJsonElement(raw).jsonObject
        val members = root["members"]?.jsonArray
            ?.mapNotNull { memberFromJson(it.jsonObject) } ?: emptyList()
        val latest = root["latest"]?.jsonObject?.entries?.associate {
            it.key.toLong() to (it.value.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyMap()
        val unread = root["unread"]?.jsonObject?.entries?.associate {
            it.key.toLong() to (it.value.jsonPrimitive.contentOrNull?.toIntOrNull() ?: 0)
        } ?: emptyMap()
        val blocked = root["blocked"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }
            ?.toSet() ?: emptySet()
        val squads = root["squads"]?.jsonArray
            ?.mapNotNull { squadFromJson(it.jsonObject) } ?: emptyList()
        val squadLatest = root["squad_latest"]?.jsonObject?.entries?.associate {
            val o = it.value.jsonObject
            it.key.toLong() to (o.s("body") to (o.s("created_at") ?: ""))
        } ?: emptyMap()
        Snapshot(members, latest, unread, blocked, squads, squadLatest)
    }.getOrNull()

    // ---------- one DM thread's recent messages ----------

    fun saveThread(context: Context, meId: Long, partnerId: Long, messages: List<Dm>) {
        runCatching {
            val keep = messages.takeLast(100)
            val arr = buildJsonArray {
                keep.forEach { dm ->
                    add(buildJsonObject {
                        put("id", dm.id)
                        put("sender", dm.senderId)
                        put("recipient", dm.recipientId)
                        put("body", dm.body)
                        put("created_at", dm.createdAt)
                        put("is_read", dm.isRead)
                        put("delivered_at", dm.deliveredAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            }
            prefs(context).edit()
                .putString("thread_${meId}_$partnerId", arr.toString()).apply()
        }
    }

    fun loadThread(context: Context, meId: Long, partnerId: Long): List<Dm> =
        runCatching {
            val raw = prefs(context).getString("thread_${meId}_$partnerId", null)
                ?: return emptyList()
            json.parseToJsonElement(raw).jsonArray.mapNotNull {
                val o = it.jsonObject
                runCatching {
                    Dm(
                        id = o["id"]!!.jsonPrimitive.long,
                        senderId = o["sender"]!!.jsonPrimitive.long,
                        recipientId = o["recipient"]!!.jsonPrimitive.long,
                        body = o.s("body") ?: "",
                        createdAt = o.s("created_at") ?: "",
                        isRead = o.s("is_read") == "true",
                        deliveredAt = o.s("delivered_at")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
}
