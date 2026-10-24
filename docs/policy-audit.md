# Policy publication and repository review — October 24, 2026

Publisher: Tanner Voss. Contact address supplied by the publisher: pitmastercontrollersupporter@gmail.com. Mailbox ownership/deliverability and Play Console identity were not independently verified.

## Website changes

- Replace privacy-policy.html with the supplied policy, corrected to describe the Android source currently on main.
- Publish terms.html and third-party-notices.html; remove authoring/draft wording while retaining substantive supplied legal terms.
- Preserve safety-and-terms.html as a full-content compatibility page with a canonical link to terms.html. Existing store/bookmark links remain usable without requiring JavaScript or a redirect.
- Add index.html as policy navigation. Every policy has links to all three canonical policies, publisher identity, mobile viewport and responsive styling.
- Publish complete, unmodified PytBoss Apache 2.0 license and project NOTICE text under third-party/. The standard license appendix contains illustrative bracketed fields; those are part of the license and have intentionally not been replaced.
- The public policies describe the repository Android snapshot, not unverified behavior in a future release. Effective date updated to October 24, 2026 because material data-practice descriptions were corrected.

## Corrections made to supplied claims

MainActivity.kt exposes prime() without the newer release build gate, sends HTTP through HttpURLConnection without a private-address allowlist or explicit redirect rejection, and shares raw BLE log strings. Its JS dashboard has history export and log sharing but lacks Delete Local Data, Wi-Fi provisioning, nearby SSID scanning, and the newer Share Test Report flow. The corrected policies state these limitations instead of promising unavailable protections. Android backup is explicitly disabled in AndroidManifest.xml. No advertising/analytics runtime dependencies are declared in the checked Gradle files; this is a source review, not a compiled-AAB or traffic audit.

PytBoss references are present in the PBG frame decoder and Mongoose debug-service comments. The newer iOS/local Android assets include the PytBoss catalog and bundled Apache license/NOTICE. Notices distinguish those versions and do not claim all dependencies have been exhaustively audited.

## Approval required before app or unrelated-file changes

No Android app source, manifest, Gradle files, iOS branch, historic backups, or existing README was changed by this policy update.

1. **App policy access:** The Android dashboard on main contains no Privacy Policy, Terms or Notices link and no legal screen. Adding those links requires an app asset/code change and a rebuilt release. Website publication alone does not fulfill in-app privacy access.
2. **Safety/security controls:** Priming is exposed with unverified candidate bytes; private-only networking, redirect rejection, export redaction and deletion controls from newer local work are absent on main. Reconcile the release source before distribution. Do not infer their presence from the policies or newer iOS code.
3. **Release API level:** app/build.gradle.kts targets API 35. Google's current phone/tablet new-app/update requirement is API 36. Raising target/compile SDK and testing the app requires a separate approved app change. Whether the account has an extension is unknown.
4. **Manifest cleanup:** AndroidManifest.xml repeats the same PitMonitorService declaration many times. This needs a separate build/manifest review; no claim of successful Android compilation is made.
5. **Branding:** README still says “Lockhart Local,” “READ ONLY,” and no controls/background monitoring, contrary to code. settings.gradle.kts says PitVoss; UI export names/alt text and historic backups also contain legacy branding. Application ID, package names and preference/channel keys can be compatibility identifiers, so do not rename indiscriminately. Updating README/UI text or removing backups requires separate scope approval.
6. **Play Console:** The release owner must set the live privacy URL, verify publisher/store identity, review Data safety answers against the actual release artifact and all SDKs, and complete foreground-service declarations and any necessary permission disclosures. No Console settings, store listing or Data safety form were changed or inspected.
7. **Support/legal:** Verify the supplied support address, intended retention practices and Colorado terms before offering the app. This review does not certify legal enforceability or Google Play approval. The supplied warranty/liability/indemnity provisions remain the publisher's text.
8. **iOS scope:** The policies explicitly describe the Android snapshot. Review iOS backup, sharing, permissions and foreground-only monitoring before advertising these as an iOS privacy policy. The separate iOS PR is not merged by this update.

## Official policy references checked

- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): public privacy policy, accurate practices, publisher/app identification and in-app access.
- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878): phone/tablet new apps and updates target API 36 from August 31, 2026; account-specific extensions are not verified.
- [Foreground service declarations](https://support.google.com/googleplay/android-developer/answer/13392821): declare applicable foreground service uses in Play Console.

## Verification

See the delivered mobile-checks.json for 320/390/768/1280-pixel viewport checks of the three policies, root navigation and compatibility URL. Checks cover publisher name, cross-links, authoring placeholders, script errors and horizontal overflow. Desktop/mobile rendering does not certify legal compliance. Live deployment evidence is recorded separately after publication.
