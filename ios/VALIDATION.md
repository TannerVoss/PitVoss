# Local validation

Android input hash comparison: PASS (all recorded source files unchanged).

Swift frontend syntax parse: PASS for all three Swift source files.

NetworkRPC.swift: PASS Swift type checking against the installed macOS SDK. UIKit/CoreBluetooth iOS integration has not been type checked against the iOS SDK.

Xcode project and Info.plist format: PASS with plutil.

Dashboard contract smoke test: PASS in desktop Chrome with mocked native messages; no JavaScript exceptions. This does not exercise WKWebView or hardware.

Unsigned iOS simulator Xcode build: PASS on GitHub. Evidence: https://github.com/TannerVoss/Pit_Master_Controller/actions/runs/40051896536

Signing, physical iPhone installation and smoker hardware tests: PENDING. The local Mac has Command Line Tools but no Xcode/iOS SDK.
