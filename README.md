# Instinct TV - voice remote for Android TV (and IR devices later)

An Alexa-style Android app: say the wake word, the phone beeps, say a command,
and your Android TV obeys - power, volume, channels, launching apps. The phone
is the controller; everything runs offline on the phone (no cloud, no account,
no paid service).

## How it works

```
voice -> phone mic -> Vosk (offline wake word + speech-to-text)
      -> command parser -> Android TV over WiFi (ADB protocol)
                        -> phone's built-in IR blaster (if present) [stage 2]
                        -> ESP8266 over WiFi (HTTP) -> IR / relay [stage 2]
                        -> phone camera cursor tracking           [stage 3]
```

- **Wake word + commands**: [Vosk](https://alphacephei.com/vosk/) small English
  model, fully offline. Wake word is configurable in the app (default "instinct").
- **TV control**: ADB over WiFi using
  [AdbLib](https://github.com/cgutman/AdbLib) (sources vendored in
  `app/src/main/java/com/cgutman/adblib`, BSD license - see `licenses/`).
- **IR devices (stage 2)**: two backends, picked automatically -
  1. the phone's own IR blaster (ConsumerIrManager) if the phone has one -
     codes live in `IrCodes.kt`, NEC encoder built in
  2. the ESP8266 (WeMos D1) over WiFi, firmware + protocol in `esp8266/` -
     works with the 1-ch relay you already own, no IR LED needed for
     relay on/off
  The app routes "AC on", "fan off" etc. to whichever backend is available.
- **Camera cursor (stage 3)**: planned - phone camera tracks the TV cursor.

## Voice commands (stage 1)

| Say after the wake word | What happens |
|---|---|
| turn off the TV / TV off | power toggle |
| turn on the TV | wake |
| volume up / volume down / mute | volume keys |
| channel up / channel down | channel keys |
| open YouTube / Netflix / Prime Video / Hotstar / Play Store / Spotify | launches the app |
| open settings | TV settings |
| home / back / select / pause | navigation keys |

## Setup (one time, on the TV)

1. On the Android TV: **Settings > Device Preferences > About > Build** - tap
   7 times to unlock Developer options.
2. **Developer options > Network debugging** (or "ADB debugging" + "Network
   debugging"): turn it **on**.
3. Find the TV's IP: **Settings > Network & Internet > your WiFi > status**.
4. Phone and TV must be on the **same WiFi**.

## Install (on the phone)

- Easiest: open the project in **Android Studio** (Hedgehog+), let Gradle sync,
  then **Run** on your phone.
- Or build the APK: `./gradlew assembleDebug` ->
  `app/build/outputs/apk/debug/app-debug.apk`, copy it to the phone and install.
- First run downloads the ~40 MB offline speech model (one time, needs WiFi).

## First use

1. Enter the TV IP, tap **Connect to TV**. The TV shows an
   **"Allow debugging?"** prompt - accept it (tick "always allow").
2. Tap **Start listening**, allow the microphone.
3. Say the wake word ("instinct"), wait for the beep, say a command.
   Another beep = done, a low buzz = it didn't understand.
4. Use the on-screen buttons (Power / Vol / Home / YouTube) to test the TV
   connection without voice.

## Honest limitations

- **Not yet tested against a real TV.** The app compiles, and the ADB library
  is battle-tested, but expect small first-run bugs - the on-screen log shows
  exactly what the app is doing, so send a screenshot if something misbehaves.
- Some TVs turn off WiFi/ADB in deep sleep, so "turn on" may not reach a TV
  that is fully asleep. Power off works; power on depends on the TV model.
- TVs that only offer Android 11-style "Wireless debugging" with a pairing
  code are **not** supported by this ADB library. Most Android TVs with a
  "Network debugging" toggle work fine.
- The wake word recognizer uses the English model; Hindi commands need a model
  swap (Vosk has a Hindi model) - easy follow-up.

## Repo layout

- `app/` - Android app (Kotlin, no accounts, no cloud)
- `esp8266/` - stage 2 IR blaster firmware + protocol
- `licenses/` - third-party license texts

## Licenses

App code: MIT (see LICENSE). AdbLib: BSD (licenses/ADBLIB-LICENSE.txt).
Vosk: Apache-2.0. Vosk models: Apache-2.0.
