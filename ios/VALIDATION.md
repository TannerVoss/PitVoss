# Local validation

Android input hash comparison: PASS (all recorded source files unchanged).

Swift frontend syntax parse: PASS for all three Swift source files.

NetworkRPC.swift: PASS Swift type checking against the installed macOS SDK. UIKit/CoreBluetooth iOS integration has not been type checked against the iOS SDK.

Xcode project and Info.plist format: PASS with plutil.

Dashboard contract smoke test: PASS in desktop Chrome with mocked native messages; no JavaScript exceptions. This does not exercise WKWebView or hardware.

Unsigned iOS simulator Xcode build: PASS on GitHub. Evidence: https://github.com/TannerVoss/Pit_Master_Controller/actions/runs/40051896536

Signing, physical iPhone installation and smoker hardware tests: PENDING. Xcode 27.1 and iOS 27.1 are installed locally; the local simulator compilation passes.

Local iOS 27.1 simulator launch: PASS. The bundled WKWebView dashboard reaches its safety acknowledgment screen. Added UIKit scene lifecycle support after the new SDK rejected the legacy startup path. Hardware functions and physical iPhone signing remain untested.
