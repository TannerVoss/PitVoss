# Android release synchronization

This branch starts from the repository's policy-publication merge and selectively synchronizes the newer local Android source. No local signing files, private keys, environment files, generated app output, IDE state, caches, node_modules or recovery archives were imported. The original local Android project was read only.

Imported: SDK/build configuration, current Kotlin bridge/monitor/network validation, current dashboard, model catalog, release configuration, icon/resource files, JUnit local-network tests and existing Playwright/board fixtures. Historic tracked backups are retained; they are not a release source of truth. Preserve them during review unless a separate cleanup is approved.

Policy integration: Tanner Voss publisher; complete Privacy and Terms text bundled offline; complete PytBoss notice/license retained; allowlisted public policy URLs opened by ACTION_VIEW in an external browser; no remote website gains access to LockhartNative. The blanket “Outside Software Development bears no responsibility” onboarding statement was replaced by a publisher/Terms reference that preserves nonwaivable rights, consistent with the supplied terms. Public policy content is updated in this same PR to describe the incoming Android source; the live site remains unchanged until merge.

Release restrictions retained: API 36, private/link-local destinations only, no HTTP redirects, bounded responses, redacted diagnostic exports, foreground-service monitoring and release/internal remote priming disabled. Debug priming remains experimental. No live grill commands were sent for these checks.

Signing: release artifacts are intentionally unsigned without user-provided local signing configuration. Existing upload keys and passwords are not accessed or copied. Play Console app/identity/Data safety/foreground-service declarations and final release signing remain owner tasks. These changes do not publish an app to Google Play.

Validation results are recorded in the pull request and delivered verification report. Browser checks are mocks; no claim is made that BLE, Wi-Fi provisioning, background reliability or smoker commands were hardware-tested.

## Completed checks

Unsigned release APK/AAB build, 3 JUnit tests and dashboard regression passed. Release lint completed with zero errors and 29 warnings (newer tool/SDK availability, intentional JavaScript WebView, compatibility checks and launcher resources). Target SDK 36 meets the currently checked Play requirement; availability of SDK 37 is not itself a submission requirement. The generated release BuildConfig confirms DEBUG=false and EXPERIMENTAL_PRIMING=false. Device behavior, signing and Play submission remain unverified.
