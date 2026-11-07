# MXFF Shadow Mode (JARVIS-style floating assistant)

## How it behaves
- **Idle**: a small dim, slowly breathing orb floats over every app. Silent. Drag it anywhere.
- **"Hey MXFF" / "Jarvis"** (Bengali or English): the orb glows and listens. You can say the command in the same breath: "Hey MXFF, open YouTube".
- **Reply**: a holographic head pops up once, speaks the answer with a caption, then shrinks back to the quiet shadow.
- **Follow-up**: for a few seconds after a reply you can keep talking without the wake word. If you say nothing, it goes quiet again.
- **Tap orb** = talk now (no wake word). **Long-press orb** = sleep / wake (mic off). **Tap the hologram** = stop and dismiss.
- Calls, SMS and WhatsApp are read back and need a spoken "yes / হ্যাঁ" before they run.
- While the MXFF app itself is open the shadow hides and gives the mic to the app.

## New phone actions (voice)
flashlight on/off, volume up/down/mute/max, set alarm, set timer, media play/pause/next/previous
(plus everything that existed: open apps, YouTube/Google search, maps, WhatsApp, SMS, calls, reminders).

## What you must do after copying these files
1. **Republish the web app** (Lovable). It now contains `POST /api/mxff-ask`, which the shadow uses to reach the AI.
   Without republishing, the shadow says it cannot reach the server.
2. Build the APK again (GitHub Actions or Android Studio) and install it.
3. Open MXFF -> Settings -> turn on **Floating Assistant**, allow microphone + "Display over other apps".
4. Leave the app (Home button). Say "Hey MXFF".

## Optional: protect the endpoint
Set `MXFF_APP_TOKEN` on the server and put the same value in `APP_TOKEN` in `android/app/build.gradle.kts`.

## Known limits
- Wake word uses Android's speech recogniser in a loop: needs internet, uses some battery, and some phones beep when it restarts (the app mutes system sounds for ~0.8 s around each restart where Android allows it).
- Bengali voice output needs a Bengali voice installed in Google Text-to-Speech; otherwise it shows the caption only.
- Android lets only one app use the mic at a time: a phone call or another voice app will pause it.
- Not yet included: controlling other apps by tapping/typing (needs an Accessibility Service).
