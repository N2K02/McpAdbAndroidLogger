# ADB-integrated experimental track

This branch tests the in-app integrated ADB architecture. It targets `main-adb` and must not import the Shizuku-only privilege path from `main-shizuku`.

Release labels: `McpAdbAndroidLogger-ADB-*.apk` and `experimental-adb-pr-*`.
Keep the existing Android application ID and signing identity so ADB stable and experimental packages can update one another.
Do not merge Shizuku architecture changes into this track automatically.
