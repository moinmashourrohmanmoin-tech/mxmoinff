# MXFF AI

MXFF AI is a React/TanStack web assistant with an Android Kotlin companion. The Android shell exposes voice input/output, a floating holographic orb, wake phrase listening, app launching and selected phone actions through `window.MXFFNative`.

## Quick start

- Web app: install dependencies with `npm install`, then `npm run dev`.
- Android: open the `android/` folder in Android Studio with JDK 17 and Android SDK Platform 34 installed.
- Set `WEB_URL` in `android/app/build.gradle.kts` to the deployed web app URL. The deployed web app must provide `/api/mxff-ask` for the floating assistant's server-side AI requests.

See `android/README-ANDROID.md` for build steps and `MXFF_FEATURE_AUDIT_BN.md` for a Bengali feature audit, limitations, privacy notes and setup checklist.

## Important capability limits

The current source does not implement full screen capture/visual understanding or reliable in-app TikTok UI automation for browsing feeds, reading full DMs, or sending messages. Notification access only exposes notification previews. Android requires user confirmation for screen capture and app installation. Sensitive messaging/calling actions must remain confirmation-gated.
