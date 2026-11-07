# MXFF AI — Android app

Native Kotlin app with a built-in screen (assets/www) and a direct Gemini brain (Brain.kt). It gives the UI phone powers
through `window.MXFFNative` (see `NativeBridge.kt` and `src/lib/native.ts`).

Features: Android voice input (Bengali/English), text-to-speech, open apps/URLs/camera/maps/settings,
YouTube & Google search, WhatsApp, SMS and calls (always confirmed first), reminders, floating bubble
over other apps, notification reading (kept on the phone only), wake phrase "Hey MXFF" while the app is open,
and an emergency stop.

## Build with Android Studio (free)
1. Install Android Studio (includes JDK 17 and the Android SDK).
2. File → Open → select this `android` folder. Let Gradle sync.
3. Build → Build APK(s). The APK is in `app/build/outputs/apk/`.
4. Copy the APK to your phone and install it (allow "Install unknown apps").

## Build from the command line
```
cd android
./gradlew assembleRelease      # needs JDK 17 and ANDROID_HOME or local.properties sdk.dir
```
Output: `app/build/outputs/apk/release/app-release.apk`

## Build free on GitHub
Connect the project to GitHub from Lovable. The workflow `.github/workflows/android.yml` builds the APK on every
push to `main`; download it from the run's Artifacts.

## Configuration
- No web URL or server any more: the AI key is entered inside the app (Settings).
- The release APK is signed with the debug key so it installs directly. For Play Store, create your own keystore.
- The Gemini key is typed by the user in Settings and stored only on the phone.

## First run on the phone
Open Settings inside MXFF and grant: microphone, display over other apps, notification access, SMS and phone
(only if you want those actions).


## Shadow mode
See `../SHADOW-MODE.md`: floating shadow orb, "Hey MXFF" wake word, hologram reply, new voice actions.


## Phone control (v1.1)
Voice-operate any app (TikTok, WhatsApp, Play Store …). See `../PHONE-CONTROL.md` for setup (Accessibility service +
"Allow restricted settings" on Android 13+) and the safety rules.
