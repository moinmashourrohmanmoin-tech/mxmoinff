# MXFF AI — Delivery

Complete source for the MXFF AI personal assistant: web/PWA (TanStack Start + React 19 + Tailwind v4)
and the native Android Kotlin companion. Nothing in this project requires a paid subscription or paid API.

## Contents

| Path | What it is |
| --- | --- |
| `src/` | Web app: chat with holographic orb, Bengali/English voice, memory, settings |
| `src/lib/mxff.functions.ts` | Server functions — AI brain (Gemini 1 + verification pass in "verified" mode) and web/YouTube search |
| `src/lib/brain.server.ts`, `src/lib/ai/` | AI Gateway integration; the API key stays on the server, never in frontend code |
| `src/lib/native.ts` | Web ↔ Android bridge (`window.MXFFNative`) |
| `android/` | Native Kotlin companion: floating overlay, "Hey MXFF" wake word, phone automation, WhatsApp/SMS/calls, notifications, reminders |
| `.github/workflows/android.yml` | Free GitHub Actions workflow that builds the APK on every push |
| `public/manifest.webmanifest` | Installable PWA manifest + icons |

## Run the web app locally (free)

```sh
bun install      # or npm install
bun run dev      # http://localhost:8080
```

The AI brain needs a Lovable AI key on the server (`LOVABLE_API_KEY`); inside Lovable it is provided
automatically. Locally, set the variable before `bun run dev` to enable AI replies.

## Build the Android APK (free)

```sh
cd android
./gradlew assembleRelease        # needs JDK 17 + Android SDK (see README-ANDROID.md)
```

Output: `android/app/build/outputs/apk/release/app-release.apk` (debug-signed so it installs directly;
create your own keystore for the Play Store).

Or push to GitHub connected from Lovable — the Actions workflow builds the APK for free.

## Configuration

- **Web URL inside the APK**: `buildConfigField WEB_URL` in `android/app/build.gradle.kts`.
  Point it at your published site URL so the app loads your deployment.
- **Voice wake word / floating overlay**: Android-only, enabled from Settings inside the app; the
  browser/PWA cannot show system overlays (by Android security design).

## Verified in this delivery

- Web source builds, tests pass, chat/search/memory/settings screens work.
- The included APK was compiled from exactly this `android/` source with Gradle 8.7 + JDK 17 + SDK 34.
