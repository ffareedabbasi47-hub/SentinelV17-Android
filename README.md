# SentinelV17 — Android USB OTG

This repository contains the Android Studio project for SENTINEL V17, wrapping the
existing `index.html` (BLE + Web Serial radar/telemetry app) in a native Android
WebView with added USB OTG serial support for Arduino.

## Structure

```
SentinelV17-Android/
├── .github/
│   └── workflows/
│       └── android.yml        # GitHub Actions CI — builds Debug APK
├── android/                   # Complete Android Studio project
│   ├── app/
│   ├── gradle/
│   ├── gradlew
│   ├── gradlew.bat
│   ├── settings.gradle
│   ├── build.gradle
│   ├── gradle.properties
│   └── ...
├── codemagic.yaml              # Codemagic CI config — builds Debug APK
└── README.md
```

## Building locally

```
cd android
./gradlew assembleDebug
```

The Debug APK will be produced at:
`android/app/build/outputs/apk/debug/`

## CI

- **GitHub Actions**: `.github/workflows/android.yml` builds the Debug APK on every push/PR and uploads it as a workflow artifact.
- **Codemagic**: `codemagic.yaml` builds the Debug APK on Codemagic's Free plan and emails the result.
