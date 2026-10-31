# Knight Warriors Club — Android App (Native)

Fully native Kotlin app (Jetpack Compose), decided 2026-11-01. Distributed as a
**direct APK** (no Play Store). One club, one database: the app and
knightwarriorsclub.com both read/write the same Supabase project.

- Package: `com.knightwarriorsclub.app`
- Design: iOS-26-style Liquid Glass over club artwork (artwork ships inside the APK)
- Plan: `../app-plan.md` in the club workspace (features, waves, beta-first release)

## Build phases
1. **Foundation (this repo, v0.1)** — project, glass screens standing, tab shell
2. Chat — inbox, DMs, groups, voice notes, photos (Supabase)
3. Calls — ringing-when-closed (Firebase push), earpiece audio, chat bubbles
4. Tournaments — events, Maker, leaderboard (+ news/tournaments move into the DB)
5. Extras & polish — wave 2/3 features, offline, app lock, beta with the committee

## Open it on a Mac (Android Studio)
1. Install Android Studio (free) → Open → this folder
2. Let it sync (it downloads Gradle + the Android SDK itself)
3. Plug in an Android phone with USB debugging on → ▶ Run
   The debug build installs and runs immediately.

## Signing key — READ THIS, PRESIDENT
Every release APK must be signed with the club's one keystore file.

- Create it ONCE (guided, ~2 minutes, on your own machine — never share the
  password in chat):
  Android Studio → Build → Generate Signed App Bundle/APK → Create new…
- Keep the `.jks` file + its password in **two** safe places (your Mac + a
  private backup). **Lose it and the app can never be updated** — members would
  have to uninstall and reinstall a "new" app.
- It is git-ignored here on purpose. Never commit it, never send it to anyone.

## Secrets rule
Only the Supabase **publishable** key lives in code (public-safe, same as the
website). No service-role/secret keys in this repo, ever.
