# Tamil FM Radio

A very simple Android app for personal use to play Tamil-style radio streams.

## Features
- Simple station list UI
- Play/pause controls
- Chromecast button placeholder for casting support
- No ads
- Built for personal use

## Build

```bash
./gradlew assembleDebug
```

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Notes
- Replace the example stream URLs with your preferred Tamil HD radio stations.
- For production casting, wire in the official Cast SDK and a proper Cast session manager.
