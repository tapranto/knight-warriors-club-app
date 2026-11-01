package com.knightwarriorsclub.app.data

import com.knightwarriorsclub.app.ClubConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A signed-in Supabase session. Tokens live on-device only (SessionStore). */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val authUserId: String,
    val email: String
)

/** A club member row (public columns only — private details stay committee-side). */
data class Member(
    val id: Long,
    val fullName: String,
    val username: String?,
    val avatarUrl: String?
)

/** One direct message. Threads display oldest → newest (Pranto's rule). */
data class Dm(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val body: String,
    val createdAt: String
)

/**
 * Thin Supabase REST/Auth client — the app talks to the SAME database as
 * knightwarriorsclub.com, so a message sent here appears on the website
 * and vice versa. Row Level Security does the guarding, exactly as on web.
 */
object ClubApi {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    private fun baseHeaders(accessToken: String? = null): Request.Builder {
        val b = Request.Builder()
            .addHeader("apikey", ClubConfig.SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Content-Type", "application/json")
        if (accessToken != null) b.addHeader("Authorization", "Bearer $accessToken")
        return b
    }

    private suspend fun execute(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun parseSession(body: String, emailFallback: String): Session {
        val o = json.parseToJsonElement(body).jsonObject
        val user = o["user"]?.jsonObject
        return Session(
            accessToken = o["access_token"]!!.jsonPrimitive.content,
            refreshToken = o["refresh_token"]!!.jsonPrimitive.content,
            authUserId = user?.get("id")?.jsonPrimitive?.content ?: "",
            email = user?.get("email")?.jsonPrimitive?.content ?: emailFallback
        )
    }

    /** Email + password sign-in (same accounts as the website). */
    suspend fun signIn(email: String, password: String): Result<Session> {
        val url = (ClubConfig.SUPABASE_URL + "/auth/v1/token").toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "password").build()
        val payload = buildJsonObject { put("email", email); put("password", password) }
        val req = baseHeaders().url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (code, body) = execute(req)
            if (code !in 200..299) {
                val msg = runCatching {
                    json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.content
                }.getOrNull() ?: "Sign-in failed ($code)"
                throw IllegalStateException(msg)
            }
            parseSession(body, email)
        }
    }

    /** Exchange a stored refresh token for a fresh session (stay signed in). */
    suspend fun refresh(refreshToken: String): Result<Session> {
        val url = (ClubConfig.SUPABASE_URL + "/auth/v1/token").toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "refresh_token").build()
        val payload = buildJsonObject { put("refresh_token", refreshToken) }
        val req = baseHeaders().url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (code, body) = execute(req)
            if (code !in 200..299) throw IllegalStateException("Session expired")
            parseSession(body, "")
        }
    }

    /** The three ways a sign-up can end — the same outcomes the website handles. */
    sealed interface SignUpOutcome {
        /** Email confirmation is on: account created, no session yet. */
        data object CheckEmail : SignUpOutcome
        /** The email already has an account (Supabase returns a user with no identities). */
        data object AlreadyRegistered : SignUpOutcome
        /** A session came back immediately — profile rows can be written now. */
        data class SignedIn(val session: Session) : SignUpOutcome
    }

    /**
     * Register a new member. The profile details ride in the auth metadata
     * as `kwc_profile` — exactly like the website — and [ensureMember]
     * turns them into member rows at the first sign-in.
     */
    suspend fun signUp(email: String, password: String, profile: JsonObject): Result<SignUpOutcome> {
        val url = ClubConfig.SUPABASE_URL + "/auth/v1/signup"
        val payload = buildJsonObject {
            put("email", email); put("password", password)
            put("data", buildJsonObject { put("kwc_profile", profile) })
        }
        val req = baseHeaders().url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (code, body) = execute(req)
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (code !in 200..299) {
                val msg = obj?.get("msg")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "Registration failed ($code)"
                throw IllegalStateException(msg)
            }
            val o = obj ?: throw IllegalStateException("Registration failed")
            if (o.containsKey("access_token")) {
                return@runCatching SignUpOutcome.SignedIn(parseSession(body, email))
            }
            val identities = o["identities"] as? JsonArray
            if (identities != null && identities.isEmpty()) {
                return@runCatching SignUpOutcome.AlreadyRegistered
            }
            SignUpOutcome.CheckEmail
        }
    }

    /** Send the password-reset email (the link opens the website's set-new-password page). */
    suspend fun recover(email: String): Result<Unit> {
        val url = ClubConfig.SUPABASE_URL + "/auth/v1/recover"
        val payload = buildJsonObject { put("email", email) }
        val req = baseHeaders().url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (code, body) = execute(req)
            if (code !in 200..299) {
                val msg = runCatching {
                    json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "Could not send the reset email ($code)"
                throw IllegalStateException(msg)
            }
            Unit
        }
    }

    private suspend fun restPost(
        session: Session, path: String, payload: String, prefer: String = "return=minimal"
    ): Pair<Int, String> {
        val url = (ClubConfig.SUPABASE_URL + path).toHttpUrl()
        val req = baseHeaders(session.accessToken).url(url)
            .addHeader("Prefer", prefer)
            .post(payload.toRequestBody(JSON_MEDIA)).build()
        return execute(req)
    }

    /** The signed-in user's auth record (carries the registration metadata). */
    private suspend fun authUser(session: Session): JsonObject? {
        val req = baseHeaders(session.accessToken)
            .url(ClubConfig.SUPABASE_URL + "/auth/v1/user").get().build()
        val (code, body) = execute(req)
        if (code !in 200..299) return null
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    /**
     * The website creates a new member's profile rows at first login from
     * the registration data in the auth metadata (its ensureProfile). The
     * app does the same, so an account registered on either door gets its
     * rows whichever door it first signs in through.
     */
    suspend fun ensureMember(session: Session): Member? {
        myMember(session)?.let { return it }
        return runCatching {
            val user = authUser(session) ?: return@runCatching myMember(session)
            val profile = user["user_metadata"]?.jsonObject?.get("kwc_profile")?.jsonObject
                ?: return@runCatching null
            val fullName = profile["full_name"]?.jsonPrimitive?.contentOrNull
            if (fullName.isNullOrBlank()) return@runCatching null
            val memberPayload = buildJsonObject {
                put("auth_user_id", session.authUserId)
                put("full_name", fullName)
                put("facebook_id", profile["facebook_id"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            val (mCode, mBody) = restPost(
                session, "/rest/v1/members", memberPayload.toString(), "return=representation"
            )
            if (mCode !in 200..299) return@runCatching myMember(session)
            val memberId = json.parseToJsonElement(mBody).jsonArray.firstOrNull()
                ?.jsonObject?.get("id")?.jsonPrimitive?.long
                ?: return@runCatching myMember(session)
            val dob = profile["dob"]?.jsonPrimitive?.contentOrNull
            val gender = profile["gender"]?.jsonPrimitive?.contentOrNull
            val privPayload = buildJsonObject {
                put("member_id", memberId)
                put("phone", profile["phone"]?.jsonPrimitive?.contentOrNull ?: "")
                put("email", session.email)
                if (dob.isNullOrBlank()) put("date_of_birth", JsonNull) else put("date_of_birth", dob)
                if (gender.isNullOrBlank()) put("gender", JsonNull) else put("gender", gender)
            }
            restPost(session, "/rest/v1/member_private", privPayload.toString())
            val games = profile["games"] as? JsonArray
            if (games != null && games.isNotEmpty()) {
                val rows = buildJsonArray {
                    games.forEach { g ->
                        val gameName = (g as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        add(buildJsonObject {
                            put("member_id", memberId)
                            put("game", gameName)
                            put("uid", JsonNull)
                            put("team_strength", JsonNull)
                        })
                    }
                }
                if (rows.isNotEmpty()) restPost(session, "/rest/v1/member_games", rows.toString())
            }
            myMember(session)
        }.getOrNull()
    }

    private suspend fun restGet(session: Session, path: String, params: Map<String, String>): JsonArray {
        val url = (ClubConfig.SUPABASE_URL + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = baseHeaders(session.accessToken).url(url).get().build()
        val (code, body) = execute(req)
        if (code !in 200..299) throw IllegalStateException("Request failed ($code)")
        return json.parseToJsonElement(body).jsonArray
    }

    private fun toMember(o: JsonObject) = Member(
        id = o["id"]!!.jsonPrimitive.long,
        fullName = o["full_name"]?.jsonPrimitive?.content ?: "Member",
        username = o["username"]?.jsonPrimitive?.content,
        avatarUrl = o["avatar_url"]?.jsonPrimitive?.content
    )

    /** The signed-in user's own member row (links auth user → member id). */
    suspend fun myMember(session: Session): Member? {
        val rows = restGet(
            session, "/rest/v1/members",
            mapOf(
                "select" to "id,full_name,username,avatar_url",
                "auth_user_id" to "eq.${session.authUserId}",
                "limit" to "1"
            )
        )
        return rows.firstOrNull()?.jsonObject?.let(::toMember)
    }

    /** All members, A–Z — the "new chat" list and inbox source. */
    suspend fun allMembers(session: Session): List<Member> {
        val rows = restGet(
            session, "/rest/v1/members",
            mapOf(
                "select" to "id,full_name,username,avatar_url",
                "order" to "full_name.asc"
            )
        )
        return rows.map { toMember(it.jsonObject) }
    }

    /**
     * One page of a DM thread, returned OLDEST FIRST for display.
     * Pass [beforeIso] to load older messages (scroll-up history).
     */
    suspend fun threadPage(
        session: Session, me: Long, them: Long, beforeIso: String? = null, pageSize: Int = 100
    ): List<Dm> {
        val params = mutableMapOf(
            "select" to "id,sender_member_id,recipient_member_id,body,created_at",
            "or" to "(and(sender_member_id.eq.$me,recipient_member_id.eq.$them)," +
                    "and(sender_member_id.eq.$them,recipient_member_id.eq.$me))",
            "order" to "created_at.desc",
            "limit" to pageSize.toString()
        )
        if (beforeIso != null) params["created_at"] = "lt.$beforeIso"
        val rows = restGet(session, "/rest/v1/direct_messages", params)
        return rows.map {
            val o = it.jsonObject
            Dm(
                id = o["id"]!!.jsonPrimitive.long,
                senderId = o["sender_member_id"]!!.jsonPrimitive.long,
                recipientId = o["recipient_member_id"]!!.jsonPrimitive.long,
                body = o["body"]?.jsonPrimitive?.content ?: "",
                createdAt = o["created_at"]!!.jsonPrimitive.content
            )
        }.reversed()
    }

    /** Send a DM. The caller writes the row, exactly like the website does. */
    suspend fun sendDm(session: Session, me: Long, them: Long, text: String): Result<Unit> {
        val url = (ClubConfig.SUPABASE_URL + "/rest/v1/direct_messages").toHttpUrl()
        val payload = buildJsonObject {
            put("sender_member_id", me); put("recipient_member_id", them); put("body", text)
        }
        val req = baseHeaders(session.accessToken).url(url)
            .addHeader("Prefer", "return=minimal")
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (code, _) = execute(req)
            if (code !in 200..299) throw IllegalStateException("Send failed ($code)")
            Unit
        }
    }

    /** Mark everything from [them] to me as read (best-effort). */
    suspend fun markRead(session: Session, me: Long, them: Long) {
        runCatching {
            val url = (ClubConfig.SUPABASE_URL + "/rest/v1/direct_messages").toHttpUrl().newBuilder()
                .addQueryParameter("recipient_member_id", "eq.$me")
                .addQueryParameter("sender_member_id", "eq.$them")
                .addQueryParameter("is_read", "eq.false")
                .build()
            val payload = buildJsonObject { put("is_read", true) }
            val req = baseHeaders(session.accessToken).url(url)
                .patch(payload.toString().toRequestBody(JSON_MEDIA)).build()
            execute(req)
        }
    }

    /** Latest activity per DM partner — orders the inbox list like the website. */
    suspend fun recentPartners(session: Session, me: Long): Map<Long, String> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/direct_messages",
                mapOf(
                    "select" to "sender_member_id,recipient_member_id,created_at",
                    "or" to "(sender_member_id.eq.$me,recipient_member_id.eq.$me)",
                    "order" to "created_at.desc",
                    "limit" to "200"
                )
            )
            val latest = LinkedHashMap<Long, String>()
            rows.forEach {
                val o = it.jsonObject
                val s = o["sender_member_id"]!!.jsonPrimitive.long
                val r = o["recipient_member_id"]!!.jsonPrimitive.long
                val partner = if (s == me) r else s
                if (!latest.containsKey(partner)) {
                    latest[partner] = o["created_at"]!!.jsonPrimitive.content
                }
            }
            latest
        }.getOrDefault(emptyMap())
    }

    /** Unread DM count per sender — feeds the inbox list and header badge. */
    suspend fun unreadCounts(session: Session, me: Long): Map<Long, Int> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/direct_messages",
                mapOf(
                    "select" to "sender_member_id",
                    "recipient_member_id" to "eq.$me",
                    "is_read" to "eq.false",
                    "limit" to "500"
                )
            )
            rows.groupingBy { it.jsonObject["sender_member_id"]!!.jsonPrimitive.long }.eachCount()
        }.getOrDefault(emptyMap())
    }
}
