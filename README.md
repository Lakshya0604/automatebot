# Automatebot - voice remote for Android TV and IR devices

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
  model, fully offline. Wake word is configurable in the app (default "bot one").
- **TV control**: ADB over WiFi using
  [AdbLib](https://github.com/cgutman/AdbLib) (sources vendored in
  `app/src/main/java/com/cgutman/adblib`, BSD license - see `licenses/`).
- **IR devices (stage 2)**: three backends, picked automatically -
  1. the phone's own IR blaster (ConsumerIrManager) if the phone has one -
     codes live in `IrCodes.kt`, NEC encoder built in
  2. **a second phone with an IR blaster**, running Automatebot's IR node over
     the house WiFi - same protocol as the ESP8266, no extra hardware at all
  3. the ESP8266 (WeMos D1) over WiFi, firmware + protocol in `esp8266/` -
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
3. Say the wake word ("bot one"), wait for the beep, say a command.
   Another beep = done, a low buzz = it didn't understand.
4. Use the on-screen buttons (Power / Vol / Home / YouTube) to test the TV
   connection without voice.

## Path 2: use a second phone's IR blaster (no hardware to buy)

The Redmi 8A has no IR blaster. If another phone in the house has one
(many Redmi Note / Mi / Poco phones do), that phone can fire IR for the Redmi:

1. Install the same APK on BOTH phones. Both on the same WiFi.
2. On the phone WITH IR: open the app, tap **"Run IR node on this phone"**.
   A notification shows its address, e.g. `192.168.1.7:8765`.
3. On the Redmi: enter that address in **"IR phone node"** (just the IP is
   fine, the app adds the port). Done - "AC on", learned buttons, everything
   IR now fires from the other phone's blaster. Keep that phone near the
   devices, pointing at them, charger plugged in.

The node speaks the same HTTP protocol as the ESP8266 firmware
(`GET /ir?name=...`, `POST /send`), so one client serves both. A phone has
no IR receiver, so *learning* new remote codes still needs the ESP8266 +
VS1838B receiver.

## What is tested (and what is not)

Automated tests (`./gradlew testDebugUnitTest`, also run by GitHub Actions
on every push):

- **TV path**: the app's real ADB client connects over a real socket to a
  fake TV adbd, completes both the RSA "Allow debugging?" handshake and the
  no-auth mode, and the test asserts the exact `shell:input keyevent ...`
  bytes arrive - see `AdbFakeTvTest.kt`.
- **Phone-to-phone IR path**: the real node server and the real client talk
  over a real socket; the test asserts named commands fire the NEC-encoded
  pattern, learned raw codes round-trip byte-for-byte, unknown commands are
  rejected and `/learn` is refused politely - see `IrNodeTest.kt`.
- **Command parser**: Hindi / Hinglish / English phrases - see `ParserTest.kt`.
- **ESP8266 firmware**: compile-verified by CI (esp8266 core 3.1.2 +
  IRremoteESP8266 2.9.0 for the WeMos D1 mini).

Not tested yet (needs the user's hardware):

- A real TV's adbd quirks - the first real connect may still show small bugs;
  the on-screen log shows exactly what the app is doing.
- ConsumerIrManager actually firing on the node phone (no IR phone here).
- Real IR range/aiming of the ESP8266 LED circuit.

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


## v0.4.0: phone-to-phone IR node + automated end-to-end tests

- New: a second phone with an IR blaster can serve as the WiFi IR node
  (`IrNodeServer` + `IrNodeService`, port 8765) - the Redmi 8A controls IR
  devices through it. Same protocol as the ESP8266.
- New: Robolectric end-to-end tests for the ADB/TV path (fake adbd, both
  auth modes) and the IR node path; parser tests; GitHub Actions CI builds
  the APK, runs the tests, compiles the firmware, and publishes releases.

## v0.2: Hindi / Hinglish / English

- Wake word is "bot one" (offline, Vosk). Change it in the app if you like.
- After the beep, the command is heard by the phone's Google speech service set to Hindi (hi-IN) with English added, so Hindi, Hinglish and English all work. Needs internet. If the service is missing or offline, it falls back to offline English.
- CommandParser understands Devanagari and Roman Hindi, e.g. "volume badhao", "टीवी बंद करो", "youtube kholo".
- Optional: paste a free Gemini API key (aistudio.google.com) in the app. Anything the parser does not recognise is sent to Gemini, which can only choose from the fixed list of commands.

## Generic IR devices, web remote, camera (v0.3.0)

- Learn any IR remote: ESP8266 + IR receiver (TSOP1738 / VS1838B on D5) + IR LED. In the app enter device name and button name, tap "Learn button", press the button on the real remote. Then say "<device> <button>", e.g. "ac on", "fan speed up", "set top box channel 25" (digits are pressed one by one if the device has 0-9 buttons).
- Web remote: https://lakshya0604.github.io/automatebot/ . Needs a free Firebase Realtime Database as the relay (see docs/FIREBASE.md).
- Website voice ("bot one") and camera (Gemini vision) run in the browser; the phone app executes the commands.
