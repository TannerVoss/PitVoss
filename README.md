# Lockhart Local — Android Wi-Fi monitor (first test build)

Connects directly to the Pit Boss Lockhart HTTP RPC interface on the same Wi-Fi network. No Mac or Bluetooth needed after installation.

## Build on Mac using Android Studio
1. Unzip this folder.
2. Open Android Studio > Open > select the **LockhartAndroid** folder (not the `app` folder).
3. Let Android Studio download/sync Gradle. Use JDK 17 (Android Studio bundled JDK works).
4. Connect Galaxy S25 Ultra with USB debugging, select it and Run. Or Build > Build APK(s) and install the resulting APK.
5. On the phone, connect to your home Wi-Fi. Open Lockhart Local and confirm grill IP (currently 192.168.1.153). Verify live temperatures.

This is intentionally READ ONLY. The HTTP GetState endpoint was verified, but Wi-Fi control RPCs have not been physically verified. Power, prime, setpoint and light commands are not sent by this app. No cloud access, no external libraries, no Wi-Fi passwords stored. The app uses HTTP on the trusted LAN; do not expose the grill to the internet. Its local RPC response may include the grill's device password; this build parses it in memory but does not display, log or store that field. Temperature history, timers and alert settings use WebView local storage. Monitoring and alarms only work while the app is open; not safety-grade.

Note: Android Studio may prompt to install Android SDK 35 and Gradle plugins. This ZIP contains source code, not a compiled APK; Android SDK is not installed in this execution environment.

## iPhone development port

The separate [iOS project](ios/README.md) reuses the current local Android dashboard with Swift/CoreBluetooth/WKWebView bridges. Open `ios/PitMaster.xcodeproj` in Xcode. See [device testing](ios/TESTING.md) and [validation status](ios/VALIDATION.md). The Android files are unchanged by this addition. iPhone/smoker operation is not yet verified; remote priming is disabled and live alerts require the iOS app to remain open.
