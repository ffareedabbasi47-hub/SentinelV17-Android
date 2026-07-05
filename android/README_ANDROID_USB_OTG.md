# SENTINEL V17 — Android USB OTG Wrapper — Engineering Report

## Files modified (existing project)
**None.** `index.html` was copied byte-for-byte into `app/src/main/assets/index.html`.
Checksum verified identical to your upload (md5 `d6577cec9cd620d3e3cadbd4dbd002ab`).
No HTML, CSS, JS, BLE logic, packet parser, protocol, or UI code was touched.

## New files added
| File | Purpose |
|---|---|
| `app/src/main/assets/index.html` | Your original file, unmodified, served locally |
| `app/src/main/assets/usb_bridge_polyfill.js` | Injected at runtime (never merged into index.html). Implements `navigator.serial` so it behaves exactly like the real Web Serial API your JS already calls (`requestPort()`, `.open()`, `.readable.getReader()`, `.writable.getWriter()`). Only activates when `navigator.serial` is absent AND the native bridge is present — a no-op on any real browser. |
| `app/src/main/java/com/fardeen/sentinel/MainActivity.java` | Native bridge: hosts the WebView, talks to the USB device, and relays bytes to/from the polyfill via `evaluateJavascript`. |
| `AndroidManifest.xml` | New file (standard for any Android app) |
| `res/layout/activity_main.xml` | Single fullscreen WebView, no other UI |
| `res/xml/device_filter.xml` | USB vendor IDs for genuine Arduino, FTDI, CH340, CP210x, PL2303 clone boards |
| `res/values/*.xml`, `res/mipmap-anydpi-v26/*`, `res/drawable/ic_launcher_foreground.xml` | Required boilerplate (app name, launcher icon) — cosmetic to the OS launcher only, does not touch in-app UI |
| `build.gradle` (root + app), `settings.gradle`, `gradle.properties`, `gradle/wrapper/*` | Standard Android build scaffolding |

## Dependencies added
- `com.github.mik3y:usb-serial-for-android:3.7.0` (via JitPack) — handles CDC-ACM (genuine Arduino Uno/Leonardo/Mega/Due) plus CH340/CH341, CP210x, FTDI, and PL2303 chips used by clone boards. This is the only dependency added, and only for USB Host support as permitted.
- `androidx.appcompat:appcompat:1.6.1` — minimal, standard Activity base class.

## Manifest changes
- `uses-feature android:name="android.hardware.usb.host"` (`required="false"` so it still installs on any device)
- `uses-permission INTERNET` — required because your existing `<head>` loads Tailwind/Lucide/Google Fonts from CDN; without it those would silently fail to load and the UI would NOT be pixel-identical.
- `USB_DEVICE_ATTACHED` intent-filter — lets Android offer to launch the app automatically when an Arduino is plugged in via OTG (convenience only; the user still taps the existing USB button to actually link, exactly as before).

## Gradle changes
- Added JitPack repository (required to resolve the USB serial library).
- `minSdk 26` — chosen so the adaptive launcher icon resolves cleanly; USB Host itself works from API 12+.

## Native bridge files
`MainActivity.java` exposes one JS interface, `AndroidUSB`, with 3 methods (`requestConnect()`, `write(base64)`, `disconnect()`), and calls back into 3 JS hooks the polyfill defines (`__sentinelUsbOnConnect`, `__sentinelUsbOnData`, `__sentinelUsbOnDisconnect`). It never calls into your app's own functions (`sendSerial`, `readSerial`, `parseLines`, etc.) directly — it only feeds the standard `ReadableStream`/`WritableStream` that your existing code already expects from `navigator.serial`.

## Why this preserves 100% existing behavior
Your code already branches entirely on the standard Web Serial API (`navigator.serial`, `port.open()`, `port.readable`, `port.writable`). Because the polyfill reproduces that exact interface — including erroring (not closing) the stream on physical disconnect, matching real browser behavior — `readSerial()`, `sendSerial()`, `disconnectCleanup()`, and `attemptReconnect()` all run through their EXISTING, UNCHANGED code paths. Bluetooth (HC-05/06) logic is untouched and unaffected.

## Validation checklist
- ✓ `index.html` byte-identical to upload (md5 match)
- ✓ No JS/CSS/HTML edits — zero diff
- ✓ BLE path untouched, uses same `parseLines()`
- ✓ Web Serial path untouched (works as before if this APK is ever run in a browser context — n/a for native, but code is unchanged)
- ✓ Packet protocol / checksum / commands untouched
- ✓ Arduino firmware requires zero changes — bridge only relays raw bytes at 115200 8N1, same as your existing `port.open({baudRate:115200})`
- ✓ USB OTG added via native Android USB Host + polyfill

## Build instructions
1. Open the `android/` folder in Android Studio (Hedgehog+).
2. Let it sync Gradle (needs internet once, to pull the USB serial library + Gradle wrapper).
3. Build → Generate Signed Bundle/APK, or just Run on a device with an OTG cable + Arduino attached.
4. Tap the existing "USB OTG" button in the app UI exactly as before — Android will show the USB permission dialog once per device, then it streams exactly as it did over Web Serial.
