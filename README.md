# PokeBuddy

Personal-use Pokémon GO overlay app for Android. Reads the phone's own screen
(MediaProjection screenshot + on-device OCR) — **never** contacts Niantic's
servers or logs into the account. Same model as PokeGenie / Calcy IV.

Full scope: see the project scope doc.

---

## Current state

The FLAG_SECURE question the architecture was gated on is answered: Pokémon GO does
**not** set it, and MediaProjection returns readable frames. Capture, OCR, IV decode,
appraisal reading, the Room index and the overlay are all built and verified on device.

See [ROADMAP.md](ROADMAP.md) for what's done, what's next, and the decisions and traps
worth not rediscovering.

### Build environment (already installed on this machine)

A command-line build toolchain is set up and verified — `gradlew.bat
:app:assembleDebug` produces a working APK. No Android Studio required to build.

- JDK 17: `C:\Users\vishn\.jdk\jdk-17.0.19+10`
- Android SDK: `C:\Users\vishn\AppData\Local\Android\Sdk` (platform-tools,
  build-tools 35.0.0, platform android-35)
- User env vars `JAVA_HOME`, `ANDROID_HOME`, and PATH are set (open a **new**
  terminal to pick them up).

### To run it on your phone

1. Enable **Developer Options → USB debugging** on the phone; plug in via USB and
   accept the "Allow USB debugging?" prompt. Confirm it's seen:
   ```
   adb devices
   ```
2. Build + install + launch:
   ```
   gradlew.bat :app:installDebug
   adb shell am start -n com.pokebuddy/.MainActivity
   ```
   (Or open the folder in Android Studio and press **Run ▶** — Android Studio is
   optional, only needed if you prefer its editor/debugger.)

### What to do on the phone

1. In PokeBuddy, tap **START CAPTURE**, grant the notification prompt (Android 13+)
   and the **screen-recording** prompt.
2. A persistent notification appears. Switch to **Pokémon GO** and open a
   Pokémon's **detail screen** (CP/HP visible).
3. Pull down the notification, tap **CAPTURE NOW**.
4. Switch back to PokeBuddy. The result box shows the verdict:
   - **READABLE ✅** — screenshot+OCR is viable, we proceed to build the real app.
   - **BLACKED OUT ❌** — Pokémon GO is FLAG_SECURE-protected on your device; stop.
5. The captured PNG is saved under the app's external files dir
   (`Android/data/com.pokebuddy/files/`) so you can eyeball it directly.

**Report back:** the "Near-black ratio" and verdict line. That's the go/no-go.

---

## Module map

| Package | Responsibility | Status |
|---|---|---|
| `capture` | MediaProjection foreground service, frame grab, screen watcher | ✅ |
| `ocr` | ML Kit on-device text recognition (bundled model, offline) | ✅ |
| `iv` | Base-stat table + appraisal/CP/HP → exact-or-candidate IV | ✅ |
| `db` | Room/SQLite local index (one row per owned Pokémon) | ✅ |
| `overlay` | `SYSTEM_ALERT_WINDOW` draw-over on encounter/detail/trade | ✅ |
| `automation` | Foreground-app detection; auto-appraise taps (opt-in) | ✅ |

## Config chosen (change freely)

- Language: Kotlin · `applicationId` `com.pokebuddy`
- `minSdk` 29 (Android 10) · `targetSdk`/`compileSdk` 35 (Android 15)
- AGP 8.7.3 · Gradle 8.9 · Kotlin 2.0.21 · JDK 17
