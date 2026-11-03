@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.knightwarriorsclub.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knightwarriorsclub.app.data.ClubApi
import com.knightwarriorsclub.app.data.Session
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The club's four games — the register form's checkbox list, same as the website. */
val CLUB_GAMES = listOf("eFootball", "PUBG", "Clash of Clans", "Call of Duty")

@Composable
fun clubFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    disabledTextColor = Color.White,
    focusedLabelColor = GoldLight, unfocusedLabelColor = Color(0xFFC4CEDC),
    disabledLabelColor = Color(0xFFC4CEDC),
    focusedBorderColor = Gold, unfocusedBorderColor = GlassRim,
    disabledBorderColor = GlassRim,
    cursorColor = GoldLight
)

@Composable
fun Modifier.clubAutofill(type: AutofillType, onFill: (String) -> Unit): Modifier {
    val autofill = LocalAutofill.current
    val node = remember(type) {
        AutofillNode(autofillTypes = listOf(type), onFill = onFill)
    }
    LocalAutofillTree.current += node
    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            if (state.isFocused) autofill?.requestAutofillForNode(node)
            else autofill?.cancelAutofillForNode(node)
        }
}

@Composable
fun ClubTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    readOnly: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    autofillType: AutofillType? = null,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = singleLine, readOnly = readOnly,
        placeholder = placeholder?.let { { Text(it, color = Color(0xFF8E9BAC)) } },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth().then(
            if (autofillType != null)
                Modifier.clubAutofill(autofillType) { v -> onChange(v) }
            else Modifier
        ),
        colors = clubFieldColors()
    )
}

/** Password field with the show/hide eye — Pranto's request, 2026-11-01. */
@Composable
fun PasswordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    autofillType: AutofillType = AutofillType.Password
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                    tint = Color(0xFFC4CEDC)
                )
            }
        },
        modifier = modifier.fillMaxWidth()
            .clubAutofill(autofillType) { v -> onChange(v) },
        colors = clubFieldColors()
    )
}

@Composable
fun FieldError(message: String?) {
    if (message != null) {
        Text(
            message, color = Color(0xFFFF9B9B), fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )
    }
}

@Composable
fun AuthHeader(title: String, sub: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(26.dp).clickable { onBack() }
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(sub, color = Color(0xFFC4CEDC), fontSize = 11.sp)
        }
    }
}

@Composable
fun GoldButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Gold else Gold.copy(alpha = 0.45f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ── Register — the SAME form as the website, field for field ─────────────

/** The website normalises BD numbers to the local 01… form before checking. */
private fun normalizePhone(raw: String): String {
    var p = raw.trim().replace(Regex("[\\s-]"), "")
    if (p.startsWith("+880")) p = "0" + p.substring(4)
    else if (p.startsWith("880") && p.length == 13) p = "0" + p.substring(3)
    return p
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onCheckEmail: (String) -> Unit,
    onSignedIn: (Session) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var facebook by remember { mutableStateOf("") }
    var selectedGames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dob by remember { mutableStateOf("") }
    var dobMillis by remember { mutableStateOf<Long?>(null) }
    var gender by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var formError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showDobPicker by remember { mutableStateOf(false) }
    val dobState = rememberDatePickerState(initialSelectedDateMillis = dobMillis)

    fun validate(): Boolean {
        val e = mutableMapOf<String, String>()
        val normalized = normalizePhone(phone)
        if (name.isBlank()) e["name"] = "Please enter your full name."
        if (!Regex("^01\\d{9}$").matches(normalized)) e["phone"] = "Please enter your phone / WhatsApp number."
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim()))
            e["email"] = "Please enter a valid email address."
        if (facebook.isBlank()) e["facebook"] = "Please enter your Facebook ID link."
        if (selectedGames.isEmpty()) e["games"] = "Please select at least one game."
        if (dob.isBlank()) e["dob"] = "Please enter your date of birth."
        if (gender == null) e["gender"] = "Please select your gender."
        if (password.isEmpty()) e["password"] = "Please create a password."
        if (confirm.isEmpty() || confirm != password)
            e["confirm"] = "Passwords do not match — please type the same password again."
        errors = e
        return e.isEmpty()
    }

    if (showDobPicker) {
        DatePickerDialog(
            onDismissRequest = { showDobPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dobState.selectedDateMillis
                    if (millis != null) {
                        dobMillis = millis
                        dob = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
                            .toLocalDate().toString()
                    }
                    showDobPicker = false
                }) { Text("OK", color = GoldLight) }
            },
            dismissButton = {
                TextButton(onClick = { showDobPicker = false }) { Text("Cancel", color = GoldLight) }
            }
        ) {
            DatePicker(state = dobState, showModeToggle = false)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp).verticalScroll(rememberScrollState())
    ) {
        AuthHeader("Member Registration", "Join Knight Warriors Club", onBack)
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            ClubTextField("Full name *", name, { name = it }, placeholder = "Your full name")
            FieldError(errors["name"])
            Spacer(Modifier.height(10.dp))
            ClubTextField(
                "Phone / WhatsApp number *", phone, { phone = it },
                keyboardType = KeyboardType.Phone, placeholder = "01…"
            )
            FieldError(errors["phone"])
            Spacer(Modifier.height(10.dp))
            ClubTextField(
                "Email *", email, { email = it },
                keyboardType = KeyboardType.Email, placeholder = "you@example.com",
                autofillType = AutofillType.EmailAddress
            )
            FieldError(errors["email"])
            Spacer(Modifier.height(10.dp))
            ClubTextField(
                "Facebook ID link *", facebook, { facebook = it },
                keyboardType = KeyboardType.Uri,
                placeholder = "https://www.facebook.com/your.profile"
            )
            FieldError(errors["facebook"])
            Spacer(Modifier.height(14.dp))
            Text("Your Games * (select one or more)", color = Color(0xFFC4CEDC), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            CLUB_GAMES.forEach { g ->
                val selected = g in selectedGames
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = if (selected) 0.14f else 0.07f))
                        .border(1.dp, GlassRim, RoundedCornerShape(12.dp))
                        .clickable {
                            selectedGames = if (selected) selectedGames - g else selectedGames + g
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (selected) Gold else Color.Transparent)
                            .border(
                                1.5.dp, if (selected) Gold else GlassRim,
                                RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Text("✓", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(g, color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            FieldError(errors["games"])
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                ClubTextField(
                    "Date of birth *", dob, {},
                    readOnly = true,
                    trailing = {
                        Icon(
                            Icons.Filled.DateRange, contentDescription = null,
                            tint = Color(0xFFC4CEDC)
                        )
                    }
                )
                Box(
                    Modifier.matchParentSize().clickable { showDobPicker = true }
                )
            }
            FieldError(errors["dob"])
            Spacer(Modifier.height(10.dp))
            Text("Gender *", color = Color(0xFFC4CEDC), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Male", "Female", "Other").forEach { g ->
                    val selected = gender == g
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Gold else Color.White.copy(alpha = 0.10f))
                            .border(
                                1.dp, if (selected) Gold else GlassRim,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { gender = g }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            g, color = if (selected) Ink else Color.White, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            FieldError(errors["gender"])
            Spacer(Modifier.height(10.dp))
            PasswordField(
                "Password *", password, { password = it },
                autofillType = AutofillType.Password
            )
            FieldError(errors["password"])
            Spacer(Modifier.height(10.dp))
            PasswordField(
                "Confirm password *", confirm, { confirm = it },
                autofillType = AutofillType.Password
            )
            FieldError(errors["confirm"])
            if (formError != null) {
                Spacer(Modifier.height(12.dp))
                Text(formError!!, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            GoldButton(if (busy) "CREATING ACCOUNT…" else "CREATE MY ACCOUNT", enabled = !busy) {
                if (!validate()) return@GoldButton
                busy = true
                formError = null
                val profile = buildJsonObject {
                    put("full_name", name.trim())
                    put("phone", normalizePhone(phone))
                    put("facebook_id", facebook.trim())
                    if (dob.isBlank()) put("dob", JsonNull) else put("dob", dob)
                    if (gender == null) put("gender", JsonNull) else put("gender", gender)
                    put("games", buildJsonArray {
                        CLUB_GAMES.filter { it in selectedGames }.forEach { add(it) }
                    })
                }
                scope.launch {
                    ClubApi.signUp(email.trim(), password, profile)
                        .onSuccess { outcome ->
                            when (outcome) {
                                is ClubApi.SignUpOutcome.CheckEmail -> onCheckEmail(email.trim())
                                is ClubApi.SignUpOutcome.AlreadyRegistered ->
                                    formError = "This email already has an account — try logging in."
                                is ClubApi.SignUpOutcome.SignedIn -> onSignedIn(outcome.session)
                            }
                        }
                        .onFailure {
                            val msg = it.message ?: "Registration failed"
                            formError = if (msg.contains("already", ignoreCase = true))
                                "This email already has an account — try logging in." else msg
                        }
                    busy = false
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    // keep the parsed DOB in step if the screen is recreated
    LaunchedEffect(Unit) {
        if (dob.isNotBlank() && dobMillis == null) {
            dobMillis = runCatching {
                LocalDate.parse(dob).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}

// ── Forgot password ──────────────────────────────────────────────────────

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentTo by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        AuthHeader("Forgot password", "Reset your club password", onBack)
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            if (sentTo == null) {
                Text(
                    "Enter the email on your club account — we'll send you a reset link. " +
                        "The link opens the club website, where you set a new password; " +
                        "then sign in here with it.",
                    color = Color(0xFFE8EDF4), fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                ClubTextField(
                    "Email", email, { email = it },
                    keyboardType = KeyboardType.Email, placeholder = "you@example.com",
                    autofillType = AutofillType.EmailAddress
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, color = Color(0xFFFF9B9B), fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                GoldButton(
                    if (busy) "SENDING…" else "SEND RESET LINK",
                    enabled = !busy && email.isNotBlank()
                ) {
                    busy = true
                    error = null
                    scope.launch {
                        ClubApi.recover(email.trim())
                            .onSuccess { sentTo = email.trim() }
                            .onFailure { error = it.message ?: "Could not send the reset email" }
                        busy = false
                    }
                }
            } else {
                Text(
                    "✓ Check your email", color = GoldLight,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A password reset link is on its way to $sentTo. " +
                        "The link works for about an hour.",
                    color = Color(0xFFE8EDF4), fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                GoldButton("BACK TO SIGN IN", enabled = true) { onBack() }
            }
        }
    }
}

// ── Check your email (after registering) ─────────────────────────────────

@Composable
fun CheckEmailScreen(email: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Image(
                painter = painterResource(R.drawable.club_logo),
                contentDescription = "Knight Warriors Club logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(18.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Check your email", color = GoldLight, fontWeight = FontWeight.Bold,
                fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "We sent a confirmation link to $email. Tap it to confirm your " +
                    "account — that email also welcomes you to the club. Then come " +
                    "back here and sign in.",
                color = Color(0xFFE8EDF4), fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            GoldButton("BACK TO SIGN IN", enabled = true) { onBack() }
        }
    }
}

// ── Verify signup code (OTP, after registering) ──────────────────────────

@Composable
fun VerifyCodeScreen(email: String, onVerified: (Session) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Image(
                painter = painterResource(R.drawable.club_logo),
                contentDescription = "Knight Warriors Club logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(18.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Enter your code", color = GoldLight, fontWeight = FontWeight.Bold,
                fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "We sent a 6-digit confirmation code to $email. Type it here to " +
                    "activate your account — the code works once and expires in 1 hour.",
                color = Color(0xFFE8EDF4), fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
            ClubTextField(
                "Confirmation code", code,
                { code = it.filter { ch -> ch.isDigit() }.take(6) },
                keyboardType = KeyboardType.Number, placeholder = "123456"
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = Color(0xFFFF9B9B), fontSize = 12.sp)
            }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note!!, color = Color(0xFF9BE29B), fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            GoldButton(
                if (busy) "VERIFYING…" else "VERIFY & ACTIVATE",
                enabled = !busy && code.length == 6
            ) {
                busy = true
                error = null
                note = null
                scope.launch {
                    ClubApi.verifySignupOtp(email, code)
                        .onSuccess { onVerified(it) }
                        .onFailure {
                            error = "That code is wrong or has expired — check the " +
                                "newest email, or resend a fresh code."
                        }
                    busy = false
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Didn't get it? Resend code",
                color = GoldLight, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .clickable(enabled = !busy) {
                        busy = true
                        error = null
                        note = null
                        scope.launch {
                            ClubApi.resendSignup(email)
                                .onSuccess { note = "Fresh code sent — check your inbox (and spam folder)." }
                                .onFailure { error = "Could not resend just now — wait a minute and try again." }
                            busy = false
                        }
                    }
                    .padding(vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            GoldButton("BACK TO SIGN IN", enabled = true) { onBack() }
        }
    }
}
