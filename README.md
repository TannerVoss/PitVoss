# Pit Master Controller

Independent Android companion for compatible grills, including the Pit Boss Lockhart PB2180LK. Uses local Wi-Fi RPC and Bluetooth discovery/provisioning. Model listings indicate available profiles, not verified hardware compatibility.

## Build and checks

Open this folder in Android Studio. The project uses Android Gradle Plugin 9.4.0, Gradle 9.6.0 and Android SDK 36 (minimum Android API 26). Use Android Studio's bundled JDK and an installed SDK 36.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease
npm ci
PLAYWRIGHT_CHANNEL=chrome npm run test:ui
```

The UI checks use Playwright and an installed Chrome. They mock the native bridge and do not exercise smoker hardware. Unit tests cover local IPv4 validation and response-size limits. Release APK/AAB builds are unsigned unless upload-signing configuration is explicitly supplied locally. No keys or credentials are included in this repository.

## Release behavior

Release and internal builds disable remote priming. Debug builds expose experimental candidate commands for attended testing only; do not use them as verified appliance controls. Temperature changes use model setpoints and a fresh power/unit preflight. HTTP destinations are restricted to private or link-local IPv4 addresses, redirects are rejected, and response sizes are bounded. Background monitoring uses Android's connected-device foreground service and can still be interrupted.

The existing `com.pitvoss.app` application ID, Kotlin package, preference keys and notification-channel IDs remain stable for saved-data and installation compatibility. Those identifiers do not determine the publisher name. Tanner Voss is the publisher shown in the app and policies.

## Policies and data

Read policies offline from About & Help, or choose “Open published page” to open the fixed public URL in your browser:

- [Privacy Policy](https://tannervoss.github.io/Pit_Master_Controller/privacy-policy.html)
- [Terms of Service & Safety Notice](https://tannervoss.github.io/Pit_Master_Controller/terms.html)
- [Third-Party Notices](https://tannervoss.github.io/Pit_Master_Controller/third-party-notices.html)

User-requested JSON backups contain local app data and should be kept private. Reports redact addresses and credential fields; review them before sharing. Delete Local Data clears app-owned data, not settings stored on the grill or previously exported files. Android backup is disabled.

## iPhone development

The separate [iOS pull request](https://github.com/TannerVoss/Pit_Master_Controller/pull/1) contains the Swift/CoreBluetooth/WKWebView port. It builds and launches in an iPhone simulator. Physical iPhone, Bluetooth and smoker functions still require device testing. This Android update does not merge or modify that port.

See [release synchronization notes](docs/android-release-sync.md) and [policy review](docs/policy-audit.md) for validation and remaining Google Play steps. No hardware safety or store-approval claim is made.
