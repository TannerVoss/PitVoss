# Verification and device acceptance

## Checks performed on October 23, 2026

- Swift frontend syntax parse: passed for App.swift, BluetoothRPC.swift, NetworkRPC.swift.
- Property-list and Xcode project format validation: passed.
- Desktop Chrome dashboard test with a mocked iOS message handler: passed; no JavaScript exceptions. Checks dashboard startup, native method availability, safety acknowledgment, transport getter, provisioning dispatch, disabled priming and disabled background monitoring UI.
- Android source hash comparison: see validation report. Android source was not edited.
- Full unsigned iOS simulator compilation: PASS on GitHub macOS/Xcode runner. [Build evidence](https://github.com/TannerVoss/Pit_Master_Controller/actions/runs/40051896536). Local build also passes with Xcode 27.1/iOS 27.1.
- BLE, local network, smoker commands, signing and App Store upload: not tested.

`Tests/dashboard.cjs` is a desktop smoke test. With Node.js, Playwright and Chrome installed, run `node Tests/dashboard.cjs`. It uses a mocked native handler and never connects to hardware. `dashboard-preview.png` is a desktop rendering, not an iPhone screenshot.

## iPhone acceptance checklist (all pending)

Record iPhone model/iOS version, app commit, grill model/board/firmware, expected result, observed result, and pass/fail. Test on PB2180LK hardware before claiming compatibility.

1. Compile for simulator and arm64 iPhone in Xcode. Check portrait/landscape, safe areas, keyboard, dialogs, navigation, background/resume and offline startup in WKWebView.
2. Fresh install: confirm safety dialog and permission purpose strings. Deny Bluetooth/local networking, verify useful errors, then enable in Settings and retry. Check Bluetooth off/on and devices disappearing mid-scan.
3. Scan and select PB2180LK: verify UUID persistence, RSSI/name/services, 12-second completion, no callbacks from an old connection after reselection, setup timeout and disconnect reporting.
4. Check Mongoose service/characteristic IDs against firmware, acknowledged chunk writes, 4-byte big-endian length, polling without notifications, fragmented replies, reply IDs, and bounded timeouts. Verify FE0B/FE0C debug frames against physical readings, including disconnected probes and unit changes. Do not accept stale or fabricated temperatures.
5. Exercise GetState and GetCurrentIP independently. Confirm both GetState/PB.GetState variants and legacy error-message state envelopes. Try malformed replies, missing RPC methods, bad IDs and oversized replies using a local fixture server before controlling the grill.
6. Same home network: manual IP, Bluetooth IP discovery and LAN search. Try guest isolation, VPN, no Wi-Fi, no controller, permission denied and a network wider than /24. Verify public/loopback/hostname targets and HTTP redirects are rejected. Observe local HTTP behavior on each target iOS version.
7. Provision only the selected grill: manual 2.4 GHz SSID/password, Config.Set acknowledgment, Config.Save acknowledgment/reboot, changed IP, wrong credentials, BLE interruption and timeout during save. An interrupted save is an unknown outcome; check the panel/router before retrying. Inspect logs/exports to ensure no credential disclosure.
8. Under direct supervision, compare power/light commands and reported state with the physical panel. Test every Lockhart Fahrenheit/Celsius setpoint, unit or power changes during preflight, rejected commands, and missing-method fallback. Confirm success is not displayed solely from command dispatch. Keep remote priming disabled.
9. Cook/history/timers/limits: normal operation, foreground alerts, invalid probes, unit conversion, stale data, background/resume and app termination. Confirm the UI never promises monitoring while closed. Foreground timers are not guaranteed background alarms.
10. Export/import JSON in Files, cancel import, malformed/oversized files, CSV share, diagnostic copy/share, delete local data, relaunch and uninstall. Review imported Android grills: UUID reselection is required. Ensure private user files never enter the repository.
11. Repeat connection loss, app backgrounding and rapid grill/transport changes while requests are active. Check pending native replies cannot operate on a newly selected grill or apply stale temperature units. Validate controls on the physical grill after every change.
12. Before TestFlight/App Store, add production icons, owner-approved iOS privacy/legal text, real signing settings and hardware review instructions. Complete privacy disclosures and retain open-source attribution.

Local iOS 27.1 simulator launch: PASS. The bundled WKWebView dashboard reaches its safety acknowledgment screen. Added UIKit scene lifecycle support after the new SDK rejected the legacy startup path. Hardware functions and physical iPhone signing remain untested.
