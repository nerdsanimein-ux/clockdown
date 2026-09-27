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
