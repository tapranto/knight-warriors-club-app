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
import kotlinx.serialization.json.longOrNull
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
    val createdAt: String,
    val avatarUrl: String? = null,
    val motto: String? = null
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
    val createdAt: String,
    val isRead: Boolean = false,
    val deliveredAt: String? = null
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

    /** Verify the 6-digit signup code from the confirmation email (OTP flow, no link). */
    suspend fun verifySignupOtp(email: String, code: String): Result<Session> {
        val url = ClubConfig.SUPABASE_URL + "/auth/v1/verify"
        val payload = buildJsonObject {
            put("email", email); put("token", code); put("type", "signup")
        }
        val req = baseHeaders().url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (httpCode, body) = execute(req)
            if (httpCode !in 200..299) {
                val msg = runCatching {
                    json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "That code is wrong or has expired ($httpCode)"
                throw IllegalStateException(msg)
            }
            parseSession(body, email)
        }
    }

    /** Resend the signup confirmation email (it carries a fresh code). */
    suspend fun resendSignup(email: String): Result<Unit> {
        val url = ClubConfig.SUPABASE_URL + "/auth/v1/resend"
        val payload = buildJsonObject { put("type", "signup"); put("email", email) }
        val req = baseHeaders().url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return runCatching {
            val (httpCode, body) = execute(req)
            if (httpCode !in 200..299) {
                val msg = runCatching {
                    json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "Could not resend the code ($httpCode)"
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

    // ---------- Phase 3.1: FCM call doorbell ----------

    /** Register this phone's FCM token (Part 12 table member_devices). */
    suspend fun registerDeviceToken(session: Session, memberId: Long, token: String): Boolean {
        val payload = buildJsonObject {
            put("member_id", memberId)
            put("fcm_token", token)
            put("platform", "android")
        }.toString()
        val (code, _) = restPost(
            session, "/rest/v1/member_devices", payload,
            prefer = "resolution=merge-duplicates,return=minimal"
        )
        return code in 200..299
    }

    /** Tell the callee's phones the call ended unanswered (missed). */
    suspend fun sendMissedPush(
        session: Session, calleeMemberId: Long, callId: String, kind: String
    ): Boolean = runCatching {
        val payload = buildJsonObject {
            put("callee_member_id", calleeMemberId)
            put("call_id", callId)
            put("kind", kind)
            put("event", "missed")
        }.toString()
        val req = baseHeaders(session.accessToken)
            .url(ClubConfig.SUPABASE_URL + "/functions/v1/send-call-push")
            .post(payload.toRequestBody(JSON_MEDIA)).build()
        val (code, _) = execute(req)
        code in 200..299
    }.getOrDefault(false)

    /** Knock the DM recipient's phone about a new message. */
    suspend fun sendMessagePushDm(
        session: Session, recipientMemberId: Long, preview: String
    ): Boolean = runCatching {
        val payload = buildJsonObject {
            put("chat_kind", "dm")
            put("recipient_member_id", recipientMemberId)
            put("preview", preview.take(120))
        }.toString()
        val req = baseHeaders(session.accessToken)
            .url(ClubConfig.SUPABASE_URL + "/functions/v1/send-message-push")
            .post(payload.toRequestBody(JSON_MEDIA)).build()
        val (code, _) = execute(req)
        code in 200..299
    }.getOrDefault(false)

    /** Knock every other squad member's phone about a new message. */
    suspend fun sendMessagePushSquad(
        session: Session, squadId: Long, preview: String
    ): Boolean = runCatching {
        val payload = buildJsonObject {
            put("chat_kind", "squad")
            put("squad_id", squadId)
            put("preview", preview.take(120))
        }.toString()
        val req = baseHeaders(session.accessToken)
            .url(ClubConfig.SUPABASE_URL + "/functions/v1/send-message-push")
            .post(payload.toRequestBody(JSON_MEDIA)).build()
        val (code, _) = execute(req)
        code in 200..299
    }.getOrDefault(false)

    /** Ask Supabase to ring the callee's phones via FCM (send-call-push). */
    suspend fun sendCallPush(
        session: Session, calleeMemberId: Long, callId: String, kind: String
    ): Boolean = runCatching {
        val payload = buildJsonObject {
            put("callee_member_id", calleeMemberId)
            put("call_id", callId)
            put("kind", kind)
        }.toString()
        val req = baseHeaders(session.accessToken)
            .url(ClubConfig.SUPABASE_URL + "/functions/v1/send-call-push")
            .post(payload.toRequestBody(JSON_MEDIA)).build()
        val (code, _) = execute(req)
        code in 200..299
    }.getOrDefault(false)

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
            "select" to "id,sender_member_id,recipient_member_id,body,created_at,is_read",
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
                createdAt = o["created_at"]!!.jsonPrimitive.content,
                isRead = o["is_read"]?.jsonPrimitive?.contentOrNull == "true"
            )
        }.reversed()
    }

    /**
     * Tick states for one conversation: message id → (read, deliveredAt).
     * Fail-soft: before Part 13 the delivered_at select fails and the
     * is_read-only retry answers instead.
     */
    suspend fun threadStates(
        session: Session, me: Long, them: Long
    ): Map<Long, Pair<Boolean, String?>> = runCatching {
        suspend fun fetch(select: String): Map<Long, Pair<Boolean, String?>> {
            val rows = restGet(
                session, "/rest/v1/direct_messages",
                mapOf(
                    "select" to select,
                    "or" to "(and(sender_member_id.eq.$me,recipient_member_id.eq.$them)," +
                        "and(sender_member_id.eq.$them,recipient_member_id.eq.$me))",
                    "order" to "created_at.desc",
                    "limit" to "100"
                )
            )
            return rows.associate {
                val o = it.jsonObject
                (o["id"]!!.jsonPrimitive.long) to
                    ((o["is_read"]?.jsonPrimitive?.contentOrNull == "true") to
                        o["delivered_at"]?.jsonPrimitive?.contentOrNull)
            }
        }
        runCatching { fetch("id,is_read,delivered_at") }
            .getOrElse { fetch("id,is_read") }
    }.getOrDefault(emptyMap())

    /**
     * Stamp delivered_at on everything addressed to me that has not got
     * one yet (optionally only from [them]). Best-effort; silent before
     * Part 13.
     */
    suspend fun markDelivered(session: Session, me: Long, them: Long? = null) {
        runCatching {
            val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
            val b = (ClubConfig.SUPABASE_URL + "/rest/v1/direct_messages").toHttpUrl()
                .newBuilder()
                .addQueryParameter("recipient_member_id", "eq.$me")
                .addQueryParameter("delivered_at", "is.null")
            if (them != null) b.addQueryParameter("sender_member_id", "eq.$them")
            val payload = buildJsonObject { put("delivered_at", now) }
            val req = baseHeaders(session.accessToken).url(b.build())
                .patch(payload.toString().toRequestBody(JSON_MEDIA)).build()
            execute(req)
        }
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
        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: "",
        avatarUrl = o["avatar_url"]?.jsonPrimitive?.contentOrNull,
        motto = o["motto"]?.jsonPrimitive?.contentOrNull
    )

    /** Squads a member belongs to, with their role in each — profile + squad list. */
    suspend fun squadsOf(session: Session, memberId: Long): List<Pair<Squad, String>> {
        return runCatching {
            val rows = restGet(
                session, "/rest/v1/squad_members",
                mapOf(
                    "select" to "role,squads(id,name,game,captain_member_id,created_at,avatar_url,motto)",
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
                    "select" to "id,name,game,captain_member_id,created_at,avatar_url,motto",
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
            // One squad per Warrior (Pranto, 2026-11-03): any membership blocks.
            if (squadsOf(session, me).isNotEmpty()) {
                throw IllegalStateException(
                    "You're already in a squad — one squad per Warrior. Leave it before creating your own."
                )
            }
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
            // Tell the added members — bell notification (Part 15 RPC) + phone
            // push — so nobody discovers the squad by accident (Pranto, 2026-11-03).
            val targets = memberIds.filter { it != me }
            for (t in targets) notifySquadAdded(session, squad.id, t)
            if (targets.isNotEmpty()) {
                sendMessagePushSquad(
                    session, squad.id,
                    "👋 You were added to “${name.trim()}” — tap to say hello"
                )
            }
            squad
        }
    }

    /** Every squad in the club, oldest first (President's All Squads). */
    suspend fun allSquads(session: Session): List<Squad> = runCatching {
        val rows = restGet(
            session, "/rest/v1/squads",
            mapOf(
                "select" to "id,name,game,captain_member_id,created_at,avatar_url,motto",
                "order" to "created_at.asc"
            )
        )
        rows.map { toSquad(it.jsonObject) }
    }.getOrDefault(emptyList())

    /** Member count per squad id, club-wide. */
    suspend fun squadMemberCounts(session: Session): Map<Long, Int> = runCatching {
        val rows = restGet(
            session, "/rest/v1/squad_members", mapOf("select" to "squad_id")
        )
        val counts = mutableMapOf<Long, Int>()
        rows.forEach { el ->
            val sid = el.jsonObject["squad_id"]?.jsonPrimitive?.longOrNull
            if (sid != null) counts[sid] = (counts[sid] ?: 0) + 1
        }
        counts
    }.getOrDefault(emptyMap())

    /** One individual player trophy (Part 17), awarded by the President. */
    data class MemberHonour(
        val id: Long,
        val memberId: Long,
        val title: String,
        val logoUrl: String? = null,
        val createdAt: String?
    )

    private fun toMemberHonour(o: JsonObject): MemberHonour = MemberHonour(
        id = o["id"]?.jsonPrimitive?.longOrNull ?: 0,
        memberId = o["member_id"]?.jsonPrimitive?.longOrNull ?: 0,
        title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
        logoUrl = o["logo_url"]?.jsonPrimitive?.contentOrNull,
        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull
    )

    /** Individual trophies of one member, newest first. Fail-soft pre-Part 17. */
    suspend fun memberHonours(session: Session, memberId: Long): List<MemberHonour> =
        runCatching {
            val rows = restGet(
                session, "/rest/v1/member_honours",
                mapOf(
                    "member_id" to "eq.$memberId",
                    "select" to "id,member_id,title,logo_url,created_at",
                    "order" to "created_at.desc"
                )
            )
            rows.map { toMemberHonour(it.jsonObject) }
        }.getOrDefault(emptyList())

    /** President awards an individual trophy to a player (Part 17 RLS). */
    suspend fun awardMemberHonour(
        session: Session, memberId: Long, title: String, awardedBy: Long,
        logoUrl: String? = null
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("member_id", memberId); put("title", title); put("awarded_by", awardedBy)
            if (logoUrl != null) put("logo_url", logoUrl)
        }
        val (code, body) = restPost(session, "/rest/v1/member_honours", payload.toString())
        if (code !in 200..299) throw IllegalStateException("Award failed ($code): $body")
        Unit
    }

    /** President removes an individual trophy. */
    suspend fun deleteMemberHonour(session: Session, honourId: Long): Result<Unit> =
        runCatching {
            val (code, _) = restDelete(
                session, "/rest/v1/member_honours", mapOf("id" to "eq.$honourId")
            )
            if (code !in 200..299) throw IllegalStateException("Remove failed ($code)")
            Unit
        }

    /** One ICE server entry for WebRTC (from kwc-turn or the fallback). */
    data class IceServerCfg(
        val url: String,
        val username: String? = null,
        val credential: String? = null
    )

    /**
     * The club's own Metered relay list, via the kwc-turn Edge Function
     * (member session required). Empty list on any failure — callers
     * fall back to the built-in servers.
     */
    suspend fun fetchIceServers(session: Session): List<IceServerCfg> {
        val req = baseHeaders(session.accessToken)
            .url(ClubConfig.SUPABASE_URL + "/functions/v1/kwc-turn")
            .post("{}".toRequestBody(JSON_MEDIA)).build()
        val (code, body) = execute(req)
        if (code !in 200..299) return emptyList()
        return runCatching {
            val arr = json.parseToJsonElement(body).jsonObject["iceServers"]?.jsonArray
                ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val urls = o["urls"] ?: return@mapNotNull null
                val url = when (val u = urls) {
                    is JsonPrimitive -> u.contentOrNull
                    is JsonArray -> u.firstOrNull()?.jsonPrimitive?.contentOrNull
                    else -> null
                } ?: return@mapNotNull null
                IceServerCfg(
                    url = url,
                    username = o["username"]?.jsonPrimitive?.contentOrNull,
                    credential = o["credential"]?.jsonPrimitive?.contentOrNull
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Fresh copy of one squad row (name/motto/picture straight from the DB). */
    suspend fun squadById(session: Session, squadId: Long): Squad? = runCatching {
        val rows = restGet(
            session, "/rest/v1/squads",
            mapOf(
                "id" to "eq.$squadId",
                "select" to "id,name,game,captain_member_id,created_at,avatar_url,motto"
            )
        )
        rows.firstOrNull()?.jsonObject?.let(::toSquad)
    }.getOrNull()

    /** One squad trophy (Part 16). [squadName] is set on member-profile reads. */
    data class SquadHonour(
        val id: Long,
        val squadId: Long,
        val title: String,
        val logoUrl: String? = null,
        val createdAt: String?,
        val squadName: String? = null
    )

    private fun toHonour(o: JsonObject): SquadHonour = SquadHonour(
        id = o["id"]?.jsonPrimitive?.longOrNull ?: 0,
        squadId = o["squad_id"]?.jsonPrimitive?.longOrNull ?: 0,
        title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
        logoUrl = o["logo_url"]?.jsonPrimitive?.contentOrNull,
        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull,
        squadName = (o["squads"] as? JsonObject)
            ?.get("name")?.jsonPrimitive?.contentOrNull
    )

    /** Trophies of one squad, newest first. Fail-soft empty before Part 16. */
    suspend fun squadHonours(session: Session, squadId: Long): List<SquadHonour> =
        runCatching {
            val rows = restGet(
                session, "/rest/v1/squad_honours",
                mapOf(
                    "squad_id" to "eq.$squadId",
                    "select" to "id,squad_id,title,logo_url,created_at",
                    "order" to "created_at.desc"
                )
            )
            rows.map { toHonour(it.jsonObject) }
        }.getOrDefault(emptyList())

    /** Every squad trophy a member shares in, across their squads. */
    suspend fun memberSquadHonours(session: Session, memberId: Long): List<SquadHonour> {
        val ids = squadsOf(session, memberId).map { it.first.id }
        if (ids.isEmpty()) return emptyList()
        val inList = ids.joinToString(",")
        val rows = runCatching {
            restGet(
                session, "/rest/v1/squad_honours",
                mapOf(
                    "squad_id" to "in.($inList)",
                    "select" to "id,squad_id,title,logo_url,created_at,squads(name)",
                    "order" to "created_at.desc"
                )
            )
        }.getOrDefault(JsonArray(emptyList()))
        return rows.map { toHonour(it.jsonObject) }
    }

    /** Upload an award logo into the President's storage folder. */
    suspend fun uploadAwardLogo(session: Session, jpegBytes: ByteArray): Result<String> =
        runCatching {
            val path = "${session.authUserId}/award-${System.currentTimeMillis()}.jpg"
            val req = Request.Builder()
                .url(ClubConfig.SUPABASE_URL + "/storage/v1/object/avatars/$path")
                .addHeader("apikey", ClubConfig.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer ${session.accessToken}")
                .addHeader("x-upsert", "true")
                .post(jpegBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()
            val (code, body) = execute(req)
            check(code in 200..299) { "award logo upload failed (HTTP $code): ${body.take(160)}" }
            ClubConfig.SUPABASE_URL + "/storage/v1/object/public/avatars/" + path
        }

    /** President awards a trophy to a squad (Part 16 RLS enforces it). */
    suspend fun awardSquadHonour(
        session: Session, squadId: Long, title: String, awardedBy: Long,
        logoUrl: String? = null
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("squad_id", squadId); put("title", title); put("awarded_by", awardedBy)
            if (logoUrl != null) put("logo_url", logoUrl)
        }
        val (code, body) = restPost(session, "/rest/v1/squad_honours", payload.toString())
        if (code !in 200..299) throw IllegalStateException("Award failed ($code): $body")
        Unit
    }

    /** President removes a trophy (mistake fix). */
    suspend fun deleteSquadHonour(session: Session, honourId: Long): Result<Unit> =
        runCatching {
            val (code, _) = restDelete(
                session, "/rest/v1/squad_honours", mapOf("id" to "eq.$honourId")
            )
            if (code !in 200..299) throw IllegalStateException("Remove failed ($code)")
            Unit
        }

    /**
     * Bell notification for a member who was just added to a squad
     * (Part 15's security-definer RPC — direct inserts into notifications
     * are President-only). Fail-soft: before Part 15 runs, this no-ops.
     */
    suspend fun notifySquadAdded(session: Session, squadId: Long, memberId: Long): Boolean =
        runCatching {
            val payload = buildJsonObject {
                put("p_squad_id", squadId); put("p_member_id", memberId)
            }
            val (code, _) = restPost(
                session, "/rest/v1/rpc/notify_squad_added", payload.toString()
            )
            code in 200..299
        }.getOrDefault(false)

    /** Captain edits the squad's profile: name and/or motto (Part 15 columns). */
    suspend fun updateSquadInfo(
        session: Session, squadId: Long, name: String, motto: String?
    ): Result<Unit> {
        val payload = buildJsonObject {
            put("name", name)
            if (motto == null) put("motto", kotlinx.serialization.json.JsonNull)
            else put("motto", motto)
        }
        return runCatching {
            val (code, _) = restPatch(
                session, "/rest/v1/squads", mapOf("id" to "eq.$squadId"), payload.toString()
            )
            if (code !in 200..299) throw IllegalStateException("Squad update failed ($code)")
            Unit
        }
    }

    /**
     * Captain uploads the squad picture: stored under the captain's own
     * storage folder (the bucket's rule) in the avatars bucket, then the
     * squad row is pointed at it (Part 15's avatar_url; captain RLS covers
     * the update). Returns the public URL.
     */
    suspend fun uploadSquadAvatar(
        session: Session, squadId: Long, jpegBytes: ByteArray
    ): Result<String> = runCatching {
        val path = "${session.authUserId}/squad-$squadId-${System.currentTimeMillis()}.jpg"
        val req = Request.Builder()
            .url(ClubConfig.SUPABASE_URL + "/storage/v1/object/avatars/$path")
            .addHeader("apikey", ClubConfig.SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("x-upsert", "true")
            .post(jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val (code, body) = execute(req)
        check(code in 200..299) { "squad photo upload failed (HTTP $code): ${body.take(160)}" }
        val url = ClubConfig.SUPABASE_URL + "/storage/v1/object/public/avatars/" + path
        val payload = buildJsonObject { put("avatar_url", url) }
        val (pCode, _) = restPatch(
            session, "/rest/v1/squads", mapOf("id" to "eq.$squadId"), payload.toString()
        )
        check(pCode in 200..299) { "squad photo link failed (HTTP $pCode)" }
        url
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
            // Same courtesy as creation: bell notification + phone push.
            for (t in memberIds) notifySquadAdded(session, squadId, t)
            val squadName = runCatching {
                val rows2 = restGet(
                    session, "/rest/v1/squads",
                    mapOf("select" to "name", "id" to "eq.$squadId", "limit" to "1")
                )
                rows2.firstOrNull()?.jsonObject?.get("name")
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            sendMessagePushSquad(
                session, squadId,
                "👋 You were added to “${squadName ?: "a squad"}” — tap to say hello"
            )
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
