# CLAUDE.md

## Releasing

Students share one permanent link that always resolves to the newest build:

    https://github.com/nerdsanimein-ux/clockdown/releases/latest/download/Clockdown.apk

That URL only works if the **latest** GitHub release has an asset named **exactly** `Clockdown.apk`. Every release from
now on must:

1. Attach the signed APK under the exact name `Clockdown.apk`.
2. Also attach it under its versioned name (e.g. `Clockdown-1.3.apk`) — the in-app updater
   (`UpdateEngine`/`parseRelease` in `Update.kt`) just picks the first asset in the release whose name ends in
   `.apk`, so the versioned file being present and uploaded is enough; it does not care about `Clockdown.apk`
   specifically. Keep both so the permanent link and the updater both keep working.
3. Be published as a normal (non-draft, non-prerelease) release, which GitHub then marks **Latest** automatically —
   don't mark an older tag Latest by hand, and don't leave the newest release as a draft or prerelease.
4. Have a **tag that exactly equals the new `versionCode`** (see `README.md`'s Releasing section for the full
   step-by-step).

Never remove or rename an existing release's `.apk` asset after the fact — people on older versions still fetch it
by URL from the update they're on.

## Before every release: signing-key and update-path checks

Android refuses to install an update whose signing certificate doesn't match the certificate already on the
device (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, shown to the user as "Update didn't finish, this update can't be
installed over the version you have"). Since students only ever update in place via the permanent link, a key
change silently locks out everyone already on the app. Before publishing any new release:

1. **Compare signing certificates.** Build the new release APK, then run
   `apksigner verify --print-certs <new.apk>` and the same against the currently-published `Clockdown.apk` (or its
   versioned asset) from the latest GitHub release. The certificate SHA-256 digest must match exactly. If it
   doesn't, stop — do not publish. Figure out why the signing config picked up a different keystore/alias before
   doing anything else.
2. **Test the in-app update from the previous version.** Install the *currently published* release on an
   emulator (or device), then use the app's own updater (`Settings → Check for updates`) to update to the new
   build. Confirm the update actually completes and the app opens afterward — don't just trust that the build
   succeeded.

Never release if either check fails. Never work around a signature mismatch by asking a student to uninstall and
reinstall — that should be a last-resort fix for a one-off broken device, not the default plan for a release.

## Don't change things unasked

Never change build configuration (code shrinking, dependencies, signing, minSdk/targetSdk, build types) or do extra
work that wasn't asked for without asking first. If you think something is needed, propose it in the report and wait
for approval.
