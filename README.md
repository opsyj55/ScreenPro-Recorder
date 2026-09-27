# ScreenPro Recorder — Phase 1

Native Android/Kotlin starter project implementing screen recording with MediaProjection,
MediaRecorder (H.264/AAC), microphone audio, a foreground service, and MediaStore output
on Android 10+. Android 8/9 recordings are saved in the app-specific Movies directory.

## Build
1. Install Android Studio (with Android SDK Platform 35 and a compatible JDK).
2. Open this folder in Android Studio and allow Gradle sync.
3. Connect an Android device or start an emulator (screen capture is best tested on a physical device).
4. Run the `app` configuration.
5. On device, grant microphone permission and approve Android's screen-capture prompt.
6. Stop using the persistent ScreenPro notification.

## Create APK
Android Studio: Build > Build Bundle(s) / APK(s) > Build APK(s).
Debug APK: app/build/outputs/apk/debug/app-debug.apk

Or from a terminal with Gradle installed:
    gradle assembleDebug

## Current Phase 1 scope / limitations
- Microphone audio is recorded. Internal playback audio is not yet mixed into the MP4;
  Android playback-capture support has app-policy and usage restrictions and needs a
  separate AudioRecord/encoding/muxing implementation.
- Pause/resume controls, floating overlay, editing, and AdMob are planned for later phases.
- The library opens the newest saved recording in the device's video player; Android 8/9
  app-private recordings are not listed by the MediaStore library in this starter.
- Test on the intended Android versions and devices before distribution. This source has
  not been compiled or device-tested in this environment.


## Build without installing Android Studio (GitHub Actions)
This project includes `.github/workflows/android.yml`.
Upload the project to a GitHub repository, then open its Actions tab and run
**Build ScreenPro APK** (or push to `main`). When the workflow completes,
open its run and download the `ScreenPro-Recorder-debug-apk` artifact.
This creates a debug APK; it is suitable for personal testing, not a signed
Play Store release. The source has not been compiled/device-tested here.
