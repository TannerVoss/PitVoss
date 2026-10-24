# Pit Master Controller for iPhone

Separate iOS port of the existing Android app for the Pit Boss Lockhart PB2180LK. The source is the existing Android PitVoss project, including its October 22 dashboard and model catalog. Android files were only read. `android-source-sha256.json` records the input files.

Status: **compiled successfully for the iOS simulator on GitHub**, with Xcode on a macOS runner. [Successful build](https://github.com/TannerVoss/Pit_Master_Controller/actions/runs/40051896536). Xcode and the iOS SDK are absent on the authoring Mac. Swift syntax checks, project/plist validation, and desktop dashboard contract tests also pass. Physical iPhone installation, signing and smoker functions remain untested.

## Build on an Apple Silicon Mac

1. Install a current Xcode compatible with your macOS from Apple, launch it, accept its license, and install the iOS platform when prompted. Select Xcode in Settings → Locations → Command Line Tools.
2. Open `PitMaster.xcodeproj`. It has a shared `PitMaster` scheme and needs no third-party Swift packages, CocoaPods, Android tools, or project generator.
3. Select the PitMaster target → Signing & Capabilities. Choose your Apple development team and a unique bundle identifier. The included identifier is a development placeholder.
4. Select an iPhone simulator and build/run for dashboard checks. Deployment target: iOS 16.0. Simulator does not establish smoker/Bluetooth compatibility.
5. Connect and trust your physical iPhone, enable Developer Mode as required by iOS, select it as the run destination, and build/run with automatic signing.

Unsigned simulator build after Xcode installation:

```sh
xcodebuild -project PitMaster.xcodeproj -scheme PitMaster -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

No `EXCLUDED_ARCHS` workaround is needed for Apple Silicon. The project uses standard UIKit, WebKit, CoreBluetooth, Foundation, and Darwin APIs. The build above passed on GitHub; repeat it locally after installing Xcode before device testing or distribution.

## What was ported

- Bundled HTML/JavaScript dashboard, model catalog, release/legal text, images, cook history, timers, limits, model selection and diagnostics.
- `ios-bridge.js` preserves `LockhartNative` synchronous getter and asynchronous command contracts. `App.swift` hosts WKWebView, handles confirms/alerts, JSON backup import, share-sheet exports and clipboard diagnostics. CSV Blob downloads are routed to the share sheet.
- `NetworkRPC.swift` implements private IPv4 HTTP POST `/rpc`, response bounds and identifiers, redirect rejection, `GetState` / `PB.GetState` fallback, legacy controls and `PB.SendMCUCommand` with copied model frames. Temperature commands use a fresh power/unit preflight and model-specific setpoints. Commands are never retried automatically except the source app's explicit missing-method fallback.
- `BluetoothRPC.swift` implements scanning, device selection, service/characteristic discovery, notifications, big-endian 4-byte Mongoose RPC lengths, acknowledged writes sized to CoreBluetooth limits, response reads/polling, timeouts, `GetState`, `GetCurrentIP`, and credential-redacted `Config.Set` → `Config.Save` provisioning. Debug FE0B/FE0C frames feed the existing decoder.
- LAN discovery probes batches of 16 addresses in the phone's current Wi-Fi /24 segment. It supports both state RPC names. Larger networks and isolated guest networks may need manual IP entry. A search can take approximately a minute on an unresponsive segment.
- Remote priming remains disabled, matching the Android general-release gate. Native controls require the safety acknowledgment for each app launch. Control acknowledgments do not prove the grill reached the requested state; verify reported telemetry and the physical panel.

## iOS differences and permissions

- Bluetooth purpose text: `NSBluetoothAlwaysUsageDescription`. The prompt occurs when starting Bluetooth. Denial/powered-off states are reported. iOS identifies peripherals with UUIDs, not Android MAC addresses. Scan and reselect saved Android devices after import; histories and other saved data can still be imported.
- Local networking purpose text: `NSLocalNetworkUsageDescription`. Allow this when contacting the smoker. `NSAllowsLocalNetworking` permits the controller's local cleartext HTTP; native validation restricts destinations to private or link-local IPv4 and rejects redirects. No broad arbitrary-load exception, Internet proxy, multicast, location, or hotspot entitlement is requested.
- iOS has no general-purpose nearby Wi-Fi scanning API. Enter the 2.4 GHz SSID/password manually. Provisioning configures the smoker, not the phone; connect the phone to the same network in Settings. Credentials are only sent to the selected BLE peripheral and are not logged or persisted by native code.
- Continuous Android foreground-service monitoring cannot be carried over. The background checkbox is disabled. Foreground alerts are dashboard messages, not OS notifications. Live readings and timer checks can pause when the app is suspended; elapsed timers are re-evaluated when it resumes. No background-mode or notification entitlement is declared. Bluetooth disconnects on backgrounding and must be reselected after returning. Do not rely on this app for unattended monitoring.
- Backup/export uses the iOS share sheet; import uses Files. Temporary share files are removed when sharing finishes. Review backups/diagnostics before sharing. WKWebView local storage persists app data until deleted or app data is cleared.

Apple references: [Bluetooth purpose string](https://developer.apple.com/documentation/bundleresources/information-property-list/nsbluetoothalwaysusagedescription), [local network privacy](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy), [local HTTP exception](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking), [iOS Wi-Fi API overview](https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview).

## Distribution and App Store

This is a development project, not an App Store submission. Before release: complete the device matrix in TESTING.md, add a final app icon and screenshots, select your real bundle identifier/team, review privacy and safety text for the iOS behavior, and complete App Store Connect privacy disclosures based on actual data practices. Reused legal text may describe Android behavior and requires an owner review before distribution.

Include the PytBoss license/notice already in the Web folder. Confirm branding and third-party rights; do not imply Pit Boss endorsement or broad model compatibility. Give reviewers clear hardware/setup instructions and evidence of the native controller functions. Review [Apple's current guidelines](https://developer.apple.com/app-store/review/guidelines/), including 4.2 functionality, 2.5.4 background services, and privacy requirements. Do not promise acceptance. No Apple developer credentials, signing certificates, paid memberships, or provisioning profiles are included.
