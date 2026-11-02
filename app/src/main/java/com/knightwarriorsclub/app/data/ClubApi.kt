package com.knightwarriorsclub.app.data

import com.knightwarriorsclub.app.ClubConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    val avatarUrl: String?,
    val role: String = "Member",
    val bio: String? = null,
    val favouriteGame: String? = null,
    val createdAt: String? = null,
    val coverUrl: String? = null
)

/** A squad (group chat) — Messenger-style since 2026-11-02: anyone can
 *  create one, the creator is its Captain, one owned squad per member. */
data class Squad(
    val id: Long,
    val name: String,
    val game: String?,
    val captainId: Long,
    val createdAt: String
)

/** One member of a squad, with their squad role ('Captain' / 'Member'). */
data class SquadMemberRow(
    val member: Member,
    val squadRole: String
)

/** One message inside a squad chat. */
data class SquadMsg(
    val id: Long,
    val squadId: Long,
    val senderId: Long,
    val body: String,
    val createdAt: String
)

/** One club broadcast — the "Everyone" room's content, posted by the President. */
data class Broadcast(
    val id: Long,
    val senderId: Long,
    val title: String,
    val body: String,
    val createdAt: String
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
        // contentOrNull, NOT content: a JSON null must stay null, otherwise
        // the UI prints the literal text "@null" (Pranto's screenshot, v0.3.0)
        username = o["username"]?.jsonPrimitive?.contentOrNull,
        avatarUrl = o["avatar_url"]?.jsonPrimitive?.contentOrNull,
        role = o["role"]?.jsonPrimitive?.contentOrNull ?: "Member",
        bio = o["bio"]?.jsonPrimitive?.contentOrNull,
        favouriteGame = o["favourite_game"]?.jsonPrimitive?.contentOrNull,
        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull,
        coverUrl = o["cover_url"]?.jsonPrimitive?.contentOrNull
    )

    private const val MEMBER_SELECT = "id,full_name,username,avatar_url,cover_url,role,bio,favourite_game,created_at"

    /** The signed-in user's own member row (links auth user → member id). */
    suspend fun myMember(session: Session): Member? {
        val rows = restGet(
            session, "/rest/v1/members",
            mapOf(
                "select" to MEMBER_SELECT,
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
                "select" to MEMBER_SELECT,
                "order" to "full_name.asc"
            )
        )
        return rows.map { toMember(it.jsonObject) }
    }

    /** One member by id — profile pages. */
    suspend fun memberById(session: Session, id: Long): Member? {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/members",
                mapOf("select" to MEMBER_SELECT, "id" to "eq.$id", "limit" to "1")
            )
            rows.firstOrNull()?.jsonObject?.let(::toMember)
        }.getOrNull()
    }

    /** A member's games (names; uid/strength are the owner's own detail). */
    suspend fun memberGames(session: Session, memberId: Long): List<Triple<String, String?, Int?>> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/member_games",
                mapOf(
                    "select" to "game,uid,team_strength",
                    "member_id" to "eq.$memberId",
                    "order" to "game.asc"
                )
            )
            rows.map {
                val o = it.jsonObject
                Triple(
                    o["game"]?.jsonPrimitive?.contentOrNull ?: "",
                    o["uid"]?.jsonPrimitive?.contentOrNull,
                    o["team_strength"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---------- website data files (Home's live cards) ----------

    /** A club tournament from the website's data/tournaments.json. */
    data class FeaturedTournament(
        val name: String, val game: String, val format: String?, val status: String
    )

    private suspend fun fetchSiteJson(path: String): JsonObject? = runCatching {
        val req = Request.Builder().url("https://knightwarriorsclub.com$path").get().build()
        val (code, body) = execute(req)
        if (code in 200..299) json.parseToJsonElement(body).jsonObject else null
    }.getOrNull()

    /**
     * The tournament the Home page should feature — the same choice the
     * website makes: a running one first, else the next upcoming one.
     * Read live from the website's own data file, so publishing a new
     * tournament updates the app with no new APK (Pranto, 2026-11-02).
     */
    suspend fun featuredTournament(): FeaturedTournament? {
        val root = fetchSiteJson("/data/tournaments.json") ?: return null
        val list = runCatching {
            root["tournaments"]!!.jsonArray.map { it.jsonObject }
        }.getOrDefault(emptyList())
        fun pick(vararg statuses: String) = list.firstOrNull { o ->
            (o["status"]?.jsonPrimitive?.contentOrNull ?: "") in statuses
        }
        val chosen = pick("running", "live") ?: pick("upcoming") ?: return null
        return FeaturedTournament(
            name = chosen["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            game = chosen["game"]?.jsonPrimitive?.contentOrNull ?: "",
            format = chosen["format"]?.jsonPrimitive?.contentOrNull,
            status = chosen["status"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    /** The newest website announcement headline (Home's news card). */
    suspend fun latestAnnouncementTitle(): String? {
        val root = fetchSiteJson("/data/announcements.json") ?: return null
        return runCatching {
            root["announcements"]!!.jsonArray.firstOrNull()?.jsonObject
                ?.get("title")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    // ---------- profile editing (v0.4.0-beta3) ----------

    /** Update the signed-in member's own profile fields. */
    suspend fun updateMemberProfile(
        session: Session, memberId: Long, fields: Map<String, JsonElement>
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }
        val (code, _) = restPatch(
            session, "/rest/v1/members", mapOf("id" to "eq.$memberId"), payload.toString()
        )
        check(code in 200..299) { "profile update failed (HTTP $code)" }
    }

    /** Update one of the member's game rows (UID / team strength). */
    suspend fun updateMemberGame(
        session: Session, memberId: Long, game: String, uid: String?, strength: Int?
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("uid", uid?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "team_strength",
                strength?.let { JsonPrimitive(it) } ?: JsonNull
            )
        }
        val (code, _) = restPatch(
            session, "/rest/v1/member_games",
            mapOf("member_id" to "eq.$memberId", "game" to "eq.$game"),
            payload.toString()
        )
        check(code in 200..299) { "game update failed (HTTP $code)" }
    }

    /**
     * Upload a new profile photo, mirroring the website exactly:
     * avatars bucket, path <authUserId>/avatar-<millis>.jpg, JPEG.
     * Returns the public URL to store in members.avatar_url.
     */
    suspend fun uploadAvatar(session: Session, jpegBytes: ByteArray): Result<String> =
        runCatching {
            val path = "${session.authUserId}/avatar-${System.currentTimeMillis()}.jpg"
            val req = Request.Builder()
                .url(ClubConfig.SUPABASE_URL + "/storage/v1/object/avatars/$path")
                .addHeader("apikey", ClubConfig.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer ${session.accessToken}")
                .addHeader("x-upsert", "true")
                .post(jpegBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()
            val (code, body) = execute(req)
            check(code in 200..299) { "photo upload failed (HTTP $code): ${body.take(160)}" }
            ClubConfig.SUPABASE_URL + "/storage/v1/object/public/avatars/" + path
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

    /** Club broadcasts for the pinned "Everyone" room, newest first (website parity). */
    suspend fun broadcasts(session: Session): List<Broadcast> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/broadcasts",
                mapOf(
                    "select" to "id,sender_member_id,title,body,created_at",
                    "order" to "created_at.desc",
                    "limit" to "50"
                )
            )
            rows.map {
                val o = it.jsonObject
                Broadcast(
                    id = o["id"]!!.jsonPrimitive.long,
                    senderId = o["sender_member_id"]?.jsonPrimitive?.long ?: 0L,
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
                    createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        }.getOrDefault(emptyList())
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

    // ---------- generic PATCH / DELETE helpers ----------

    private suspend fun restPatch(
        session: Session, path: String, params: Map<String, String>, payload: String
    ): Pair<Int, String> {
        val url = (ClubConfig.SUPABASE_URL + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = baseHeaders(session.accessToken).url(url)
            .addHeader("Prefer", "return=minimal")
            .patch(payload.toRequestBody(JSON_MEDIA)).build()
        return execute(req)
    }

    private suspend fun restDelete(
        session: Session, path: String, params: Map<String, String>
    ): Pair<Int, String> {
        val url = (ClubConfig.SUPABASE_URL + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = baseHeaders(session.accessToken).url(url).delete().build()
        return execute(req)
    }

    // ---------- squads (Part 11 rules: open creation, one owned, Captain = creator) ----------

    private fun toSquad(o: JsonObject) = Squad(
        id = o["id"]!!.jsonPrimitive.long,
        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Squad",
        game = o["game"]?.jsonPrimitive?.contentOrNull,
        captainId = o["captain_member_id"]!!.jsonPrimitive.long,
        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
    )

    /** Squads a member belongs to, with their role in each — profile + squad list. */
    suspend fun squadsOf(session: Session, memberId: Long): List<Pair<Squad, String>> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/squad_members",
                mapOf(
                    "select" to "role,squads(id,name,game,captain_member_id,created_at)",
                    "member_id" to "eq.$memberId"
                )
            )
            rows.mapNotNull {
                val o = it.jsonObject
                val sq = o["squads"]?.jsonObject ?: return@mapNotNull null
                toSquad(sq) to (o["role"]?.jsonPrimitive?.contentOrNull ?: "Member")
            }
        }.getOrDefault(emptyList())
    }

    /** The squad I captain, if any (one-owned rule). */
    suspend fun myOwnedSquad(session: Session, me: Long): Squad? {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/squads",
                mapOf(
                    "select" to "id,name,game,captain_member_id,created_at",
                    "captain_member_id" to "eq.$me",
                    "limit" to "1"
                )
            )
            rows.firstOrNull()?.jsonObject?.let(::toSquad)
        }.getOrNull()
    }

    /** Create a squad: I am Captain automatically; picked members join as Member. */
    suspend fun createSquad(
        session: Session, me: Long, name: String, memberIds: List<Long>
    ): Result<Squad> {
        return runCatching {
            val payload = buildJsonObject {
                put("name", name); put("captain_member_id", me)
            }
            val (code, body) = restPost(
                session, "/rest/v1/squads", payload.toString(), "return=representation"
            )
            if (code !in 200..299) {
                if (code == 409 || body.contains("23505")) {
                    throw IllegalStateException("You already lead a squad — disband it before creating another.")
                }
                throw IllegalStateException("Could not create the squad ($code)")
            }
            val squad = json.parseToJsonElement(body).jsonArray.firstOrNull()
                ?.jsonObject?.let(::toSquad) ?: throw IllegalStateException("Could not create the squad")
            val rows = buildJsonArray {
                add(buildJsonObject {
                    put("squad_id", squad.id); put("member_id", me); put("role", "Captain")
                })
                memberIds.filter { it != me }.forEach { mid ->
                    add(buildJsonObject {
                        put("squad_id", squad.id); put("member_id", mid); put("role", "Member")
                    })
                }
            }
            val (mCode, _) = restPost(session, "/rest/v1/squad_members", rows.toString())
            if (mCode !in 200..299) throw IllegalStateException("Squad created, but members could not be added")
            squad
        }
    }

    /** Everyone in a squad, Captain first. */
    suspend fun squadMembers(session: Session, squadId: Long): List<SquadMemberRow> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/squad_members",
                mapOf(
                    "select" to "role,members($MEMBER_SELECT)",
                    "squad_id" to "eq.$squadId"
                )
            )
            rows.mapNotNull {
                val o = it.jsonObject
                val m = o["members"]?.jsonObject ?: return@mapNotNull null
                SquadMemberRow(toMember(m), o["role"]?.jsonPrimitive?.contentOrNull ?: "Member")
            }.sortedWith(compareByDescending<SquadMemberRow> { it.squadRole == "Captain" }
                .thenBy { it.member.fullName })
        }.getOrDefault(emptyList())
    }

    /** One page of a squad chat, OLDEST FIRST (same display rule as DMs). */
    suspend fun squadThreadPage(
        session: Session, squadId: Long, beforeIso: String? = null, pageSize: Int = 100
    ): List<SquadMsg> {
        val params = mutableMapOf(
            "select" to "id,squad_id,sender_member_id,body,created_at",
            "squad_id" to "eq.$squadId",
            "order" to "created_at.desc",
            "limit" to pageSize.toString()
        )
        if (beforeIso != null) params["created_at"] = "lt.$beforeIso"
        val rows = restGet(session, "/rest/v1/squad_messages", params)
        return rows.map {
            val o = it.jsonObject
            SquadMsg(
                id = o["id"]!!.jsonPrimitive.long,
                squadId = o["squad_id"]!!.jsonPrimitive.long,
                senderId = o["sender_member_id"]!!.jsonPrimitive.long,
                body = o["body"]?.jsonPrimitive?.content ?: "",
                createdAt = o["created_at"]!!.jsonPrimitive.content
            )
        }.reversed()
    }

    suspend fun sendSquadMessage(
        session: Session, squadId: Long, me: Long, text: String
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("squad_id", squadId); put("sender_member_id", me); put("body", text)
        }
        return runCatching {
            val (code, _) = restPost(session, "/rest/v1/squad_messages", payload.toString())
            if (code !in 200..299) throw IllegalStateException("Send failed ($code)")
            Unit
        }
    }

    /** Newest message in a squad (inbox ordering/snippet); null when empty. */
    suspend fun latestSquadMessage(session: Session, squadId: Long): SquadMsg? {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/squad_messages",
                mapOf(
                    "select" to "id,squad_id,sender_member_id,body,created_at",
                    "squad_id" to "eq.$squadId",
                    "order" to "created_at.desc",
                    "limit" to "1"
                )
            )
            rows.firstOrNull()?.jsonObject?.let {
                SquadMsg(
                    id = it["id"]!!.jsonPrimitive.long,
                    squadId = it["squad_id"]!!.jsonPrimitive.long,
                    senderId = it["sender_member_id"]!!.jsonPrimitive.long,
                    body = it["body"]?.jsonPrimitive?.content ?: "",
                    createdAt = it["created_at"]!!.jsonPrimitive.content
                )
            }
        }.getOrNull()
    }

    suspend fun renameSquad(session: Session, squadId: Long, name: String): Result<Unit> {
        val payload = buildJsonObject { put("name", name) }
        return runCatching {
            val (code, _) = restPatch(
                session, "/rest/v1/squads", mapOf("id" to "eq.$squadId"), payload.toString()
            )
            if (code !in 200..299) throw IllegalStateException("Rename failed ($code)")
            Unit
        }
    }

    suspend fun addSquadMembers(
        session: Session, squadId: Long, memberIds: List<Long>
    ): Result<Unit> {
        if (memberIds.isEmpty()) return Result.success(Unit)
        val rows = buildJsonArray {
            memberIds.forEach { mid ->
                add(buildJsonObject {
                    put("squad_id", squadId); put("member_id", mid); put("role", "Member")
                })
            }
        }
        return runCatching {
            val (code, _) = restPost(session, "/rest/v1/squad_members", rows.toString())
            if (code !in 200..299) throw IllegalStateException("Could not add members ($code)")
            Unit
        }
    }

    suspend fun removeSquadMember(
        session: Session, squadId: Long, memberId: Long
    ): Result<Unit> {
        return runCatching {
            val (code, _) = restDelete(
                session, "/rest/v1/squad_members",
                mapOf("squad_id" to "eq.$squadId", "member_id" to "eq.$memberId")
            )
            if (code !in 200..299) throw IllegalStateException("Remove failed ($code)")
            Unit
        }
    }

    /** Captain disbands: deleting the squad row cascades members + messages. */
    suspend fun deleteSquad(session: Session, squadId: Long): Result<Unit> {
        return runCatching {
            val (code, _) = restDelete(
                session, "/rest/v1/squads", mapOf("id" to "eq.$squadId")
            )
            if (code !in 200..299) throw IllegalStateException("Disband failed ($code)")
            Unit
        }
    }

    // ---------- member blocks (Part 11) — fail-soft until the SQL is run ----------

    /** Members I have blocked. */
    suspend fun myBlockedIds(session: Session, me: Long): Set<Long> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/member_blocks",
                mapOf("select" to "blocked_member_id", "blocker_member_id" to "eq.$me")
            )
            rows.map { it.jsonObject["blocked_member_id"]!!.jsonPrimitive.long }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Members who have blocked me (their chats/calls should not reach me either). */
    suspend fun blockedByIds(session: Session, me: Long): Set<Long> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/member_blocks",
                mapOf("select" to "blocker_member_id", "blocked_member_id" to "eq.$me")
            )
            rows.map { it.jsonObject["blocker_member_id"]!!.jsonPrimitive.long }.toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun blockMember(session: Session, me: Long, them: Long): Result<Unit> {
        val payload = buildJsonObject {
            put("blocker_member_id", me); put("blocked_member_id", them)
        }
        return runCatching {
            val (code, _) = restPost(session, "/rest/v1/member_blocks", payload.toString())
            if (code !in 200..299) throw IllegalStateException("Block failed ($code)")
            Unit
        }
    }

    suspend fun unblockMember(session: Session, me: Long, them: Long): Result<Unit> {
        return runCatching {
            val (code, _) = restDelete(
                session, "/rest/v1/member_blocks",
                mapOf("blocker_member_id" to "eq.$me", "blocked_member_id" to "eq.$them")
            )
            if (code !in 200..299) throw IllegalStateException("Unblock failed ($code)")
            Unit
        }
    }
}
