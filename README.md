# Clockdown

<img src="docs/icon.png" width="96" alt="Clockdown icon" align="right">

Clockdown counts down to the things you don't want to miss, so you can stop checking the clock.

- **Countdowns for anything.** Create your own timers with a name, date, time, emoji and colour, and get an alarm before they start.
- **Your classes, automatically.** Connect your Amizone account once and your timetable shows up with live countdowns and alarms. You sign in on Amizone's own page. To make signing in again quicker, your ID and password can be kept encrypted on your phone (never sent anywhere except Amizone's own sign-in page), and you can delete them any time in Settings.
- **Home screen widgets.** A live ticking timer, your next classes, or a list of what's coming up, in your choice of looks: solid cards, gradients, bold, see-through glass and mono that adapt to your wallpaper, and more. Set one look for all class timers, or give each widget its own. After saving a timer, one tap puts it on your home screen.
- **Private by design.** No ads, no tracking, no analytics. Everything stays on your phone. The only internet use is talking to Amizone (if you connect it) and checking this page for new versions.

## Install (Android 8 or newer)

1. On your phone, open the [latest release](../../releases/latest) and download the file ending in `.apk`.
2. Open the downloaded file. If Android asks, allow your browser to "install unknown apps", then tap **Install**.
3. Open **Clockdown** and allow notifications when asked, so alarms can reach you.

**Android shows a warning?** That's normal for any app that doesn't come from the Play Store; it's about where the app came from, not what it does. If you see "Blocked by Play Protect" or "Harmful app", tap **More details** (or the small arrow), then **Install anyway**. If Android offers to scan the app first, you can tap **Scan**. The same steps are in the app under **Settings > How to install**.

**Updating:** after the first install, Clockdown checks for new versions by itself. When one is ready, a dot appears on the Settings button; open **Settings → Check for updates** and tap **Download**.

## For developers

Build: `./gradlew assembleDebug` (needs JDK 21 and the Android SDK). Run the tests with `./gradlew testDebugUnitTest`.

Releasing:

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName` (for example `1.1`).
2. Put your signing details in `local.properties` (`release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword`). That file is git-ignored. Then run `./gradlew assembleRelease`.
3. Create a GitHub release whose **tag is exactly the new `versionCode`** (for example `2`), whose title is the version name, whose description lists what's new in plain words, and attach `app/build/outputs/apk/release/app-release.apk`.

Keep your signing key safe and backed up. Android only installs an update if it is signed with the same key as the version already on the phone.
