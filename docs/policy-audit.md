# Policy and release review — October 24, 2026

Publisher: Tanner Voss. Supplied support address: pitmastercontrollersupporter@gmail.com. Mailbox ownership/delivery and Play Console identity are not independently verified.

## Resolved by this Android synchronization

The earlier audit concerned an old GitHub Android snapshot. The approved newer local source provides target/compile API 36, a single monitoring-service declaration, release/internal priming disabled, private IPv4 HTTP allowlisting, redirect rejection, bounded responses, report redaction, app-data deletion and Wi-Fi provisioning. Those features were imported for review, preserving the package/application ID and local data keys.

The app now bundles current policy text with Tanner Voss as publisher, complete PytBoss license/attribution and three fixed public policy links opened in the external browser. The published HTML policies in this PR describe that incoming source. The old safety-and-terms.html address remains a full-content compatibility page. No placeholder publisher or draft authoring instructions remain. Illustrative bracketed fields in the verbatim Apache license appendix are part of the license and are intentionally retained.

README/public UI branding is Pit Master Controller. Historical backup files and legacy com.pitvoss.app/preference/channel identifiers remain for history and compatibility; they are not proof of a different publisher. No recovery archives or local secrets were newly imported.

## Still required before Google Play release

- Review the exact release artifact and Play Console's privacy URL, publisher/app identity and Data safety declarations. Console access and the currently uploaded artifact were not inspected.
- Declare the connected-device foreground-service use in Play Console; verify permission explanations and actual behavior on supported Android versions. API 36 configuration does not itself establish Play approval.
- Build/sign the final release with the existing upload key locally, using a valid monotonically increasing versionCode for Play. The imported version is 3 / 0.3.0-beta2; the highest uploaded version is unknown. No upload key is included.
- Test BLE, Wi-Fi setup, background monitoring, connection failures and all allowed controls on the physical smoker under supervision. Disabled release priming must remain disabled. Acknowledgments are not proof of appliance state.
- Confirm actual report contents and any third-party SDK behavior against the final artifact. Redaction has limits; users must review reports before sending. Test local data deletion and backup behavior on physical devices.
- Verify the supplied support contact, intended support retention and legal provisions. This is an implementation/source audit, not certification of legal enforceability.
- Review the separate iOS version's privacy practices before using these Android-specific policies for an iOS store submission.

## Official references

- [Google Play User Data](https://support.google.com/googleplay/android-developer/answer/10144311): accurate data practices, public policy, app/publisher identity and in-app access.
- [Target API requirement](https://support.google.com/googleplay/android-developer/answer/11926878): API 36 for current phone/tablet new apps and updates, subject to account-specific eligibility/extensions.
- [Foreground services](https://support.google.com/googleplay/android-developer/answer/13392821): applicable Play Console service declarations.

No Play Console settings, Android signing credentials or iOS source were modified by this update. The app is not automatically released when this PR is merged.
