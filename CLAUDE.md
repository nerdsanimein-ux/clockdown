# CLAUDE.md

## Releasing: Google Play only

Clockdown is on Google Play (`com.rishabh.clockdown`, Play Console organisation account Elocent). Since 1.9.3
(code 18, GitHub release `18`) every new version goes out **only through Google Play**:

1. Raise `versionCode` in `app/build.gradle.kts` above the last Play release (2.0 was 20, so the next is 21). The
   `versionName` is the user's call: ask, never pick one unasked.
2. Build with `./gradlew bundlePlay` and upload `app/build/outputs/bundle/play/app-play.aab` to the Production track
   in Play Console (see `README.md`'s Releasing section).
3. Once a release is in review, don't edit anything in Play Console: any change sends it back to the start of the
   queue.

GitHub releases are finished. Don't publish new ones: release `18` (1.9.3) stays **Latest**, so the old permanent link
`https://github.com/nerdsanimein-ux/clockdown/releases/latest/download/Clockdown.apk` keeps serving 1.9.3, which sends
people to Play. Never remove or rename an existing release's `.apk` asset — people on older versions still fetch it
by URL from the update they're on.

## Before every release: signing-key and update-path checks

Android refuses to install an update whose signing certificate doesn't match the certificate already on the
device (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Play App Signing uses Clockdown's own key (uploaded with PEPK), so
Play, GitHub and every phone share one certificate, SHA-256
`eee3a7c748df1b89d240bab155650a2bdd6a3b070a5b44712114dbff56d8856b`. A key change silently locks out everyone already
on the app. Before publishing any new release:

1. **Check the signing certificate.** Run `keytool -printcert -jarfile app/build/outputs/bundle/play/app-play.aab`
   (or `apksigner verify --print-certs` on an APK from `./gradlew assemblePlay`). The SHA-256 must be the digest
   above. If it isn't, stop — do not upload. Figure out why the signing config picked up a different keystore/alias
   before doing anything else.
2. **Test the update from the current version.** On the `play35` emulator (it has the Play Store), install the
   version currently on Play, then install the new build over it (`adb install -r` with the APK from
   `./gradlew assemblePlay`). Confirm it installs, the app opens, and nothing is lost (no welcome screens) — don't
   just trust that the build succeeded.

Never release if either check fails. Never work around a signature mismatch by asking a student to uninstall and
reinstall — that should be a last-resort fix for a one-off broken device, not the default plan for a release.

## Don't change things unasked

Never change build configuration (code shrinking, dependencies, signing, minSdk/targetSdk, build types) or do extra
work that wasn't asked for without asking first. If you think something is needed, propose it in the report and wait
for approval.
