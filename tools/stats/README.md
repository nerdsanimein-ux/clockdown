# Clockdown version counter (Google Sheet)

Each install of Clockdown (from 1.7) sends one anonymous ping a day: a random id the app made up, its version, and whether
it is still the version it was first installed with. Nothing else is stored: no IP address, no name, no Amizone ID.
Students can switch it off in Settings → Version count.

**Where to look:** the Google Sheet "Clockdown versions", tab **Summary** (works in the Google Sheets app on a phone).
It shows active installs, how many are still on an older version, and per version: active now, installed fresh,
updated to it, and already on it when counting began. It refreshes every hour; Clockdown → Refresh summary now does it at once.
The **Installs** tab is the raw data, one row per install.

## Setup (once, about two minutes)

1. Open https://sheets.new and name the sheet **Clockdown versions**.
2. Extensions → Apps Script. Delete what is there, paste all of `Code.gs` from this folder, and save.
3. In the function list at the top pick **setup**, press Run, and allow the permissions it asks for (they are for this sheet
   and the hourly refresh only).
4. Deploy → New deployment → type **Web app**. Execute as: **Me**. Who has access: **Anyone**. Deploy, and copy the
   **Web app URL**.
5. Put that URL in `STATS_URL` in `app/src/main/java/com/rishabh/clockdown/Stats.kt`. Until then the app sends nothing.

"Anyone" only lets phones post a ping; nobody can read the sheet unless you share it. Every ping is checked (a 32-character
hex id and a version number), anything else is ignored.

If you change `Code.gs` later: paste it again, then Deploy → Manage deployments → edit → Version: New version (the URL stays the same).

## Limits worth knowing

- Counting starts with the first version that sends pings. People who update into it from older versions show as
  "already on it when counting began", not as "updated".
- A reinstalled or reset phone counts as a new install (its random id is gone).
- Debug builds mark their pings and the summary leaves them out.

`node test.js` checks the counting logic without Google.
