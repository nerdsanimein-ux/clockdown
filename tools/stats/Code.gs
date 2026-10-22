// Clockdown version counter, as a Google Apps Script web app attached to a Google Sheet (see README.md in this folder).
// Each install of Clockdown sends one anonymous ping a day: a random id the app made up, its versionCode and version name,
// and whether it is still the version it was first installed with. Nothing else is stored: no IP, no name, no Amizone ID.
// The "Summary" tab is rebuilt every hour (and from the Clockdown menu); the "Installs" tab holds one row per install.

const INSTALLS = 'Installs';
const SUMMARY = 'Summary';
const HEADER = ['id', 'first_seen', 'origin', 'first_version', 'version', 'version_name', 'prev_version', 'changed_at', 'last_seen', 'debug'];
const COL = Object.fromEntries(HEADER.map((h, i) => [h, i]));
const ACTIVE_DAYS = 7; // "using it now" = pinged in the last week
const DAY_MS = 86400000;

// ---------------------------------------------------------------- pure logic (tested by test.js, no Google services)

/** Checks a ping; returns the cleaned fields or null. */
function parsePing(b) {
  if (!b || typeof b !== 'object') return null;
  const id = String(b.id || '');
  const v = Number(b.v);
  if (!/^[0-9a-f]{32}$/.test(id) || !Number.isInteger(v) || v < 1 || v > 100000) return null;
  return { id, v, vn: String(b.vn || '').replace(/[^0-9A-Za-z.\- ]/g, '').slice(0, 20), fresh: b.fresh === true, debug: b.debug === true };
}

/** The row for this install after the ping: a new row, or the existing one moved on. [row] is null for an install not seen before. */
function applyPing(row, p, now) {
  if (!row) return [p.id, now, p.fresh ? 'fresh' : 'update', p.v, p.v, p.vn, 0, now, now, p.debug ? 1 : 0];
  const r = row.slice();
  if (Number(r[COL.version]) !== p.v) { r[COL.prev_version] = Number(r[COL.version]); r[COL.changed_at] = now; }
  r[COL.version] = p.v;
  r[COL.version_name] = p.vn || r[COL.version_name];
  r[COL.last_seen] = now;
  return r;
}

/** Per version: active now, how it got there, and the totals. Test (debug) installs are left out. */
function summarize(rows, now) {
  const since = now - ACTIVE_DAYS * DAY_MS;
  const real = rows.filter((r) => Number(r[COL.debug]) !== 1);
  const by = {};
  for (const r of real) {
    const v = Number(r[COL.version]);
    const s = by[v] || (by[v] = { version: v, name: '', active: 0, fresh: 0, fresh7: 0, updated: 0, updated7: 0, before: 0, seen: 0 });
    const freshHere = r[COL.origin] === 'fresh' && Number(r[COL.first_version]) === v;
    s.name = String(r[COL.version_name] || s.name);
    s.seen++;
    if (Number(r[COL.last_seen]) >= since) s.active++;
    if (freshHere) { s.fresh++; if (Number(r[COL.first_seen]) >= since) s.fresh7++; }
    if (Number(r[COL.prev_version]) > 0) { s.updated++; if (Number(r[COL.changed_at]) >= since) s.updated7++; }
    if (r[COL.origin] === 'update' && Number(r[COL.prev_version]) === 0) s.before++;
  }
  const versions = Object.values(by).sort((a, b) => b.version - a.version);
  const latest = versions.length ? versions[0].version : 0;
  return {
    latest,
    activeNow: versions.reduce((n, s) => n + s.active, 0),
    activeOnOlder: versions.filter((s) => s.version < latest).reduce((n, s) => n + s.active, 0),
    installsSeen: real.length,
    testInstalls: rows.length - real.length,
    versions,
  };
}

// ---------------------------------------------------------------- Google side

function doPost(e) {
  let body = null;
  try { body = JSON.parse(e.postData.contents); } catch (err) { /* not JSON */ }
  const p = parsePing(body);
  if (!p) return ContentService.createTextOutput('bad ping');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000); // two phones pinging at once must not both append the same new install
  try {
    const sh = sheet(INSTALLS);
    const found = sh.getRange('A:A').createTextFinder(p.id).matchEntireCell(true).findNext();
    const now = Date.now();
    if (found) {
      const range = sh.getRange(found.getRow(), 1, 1, HEADER.length);
      range.setValues([applyPing(range.getValues()[0], p, now)]);
    } else sh.appendRow(applyPing(null, p, now));
  } finally { lock.releaseLock(); }
  return ContentService.createTextOutput('ok');
}

/** Rebuilds the Summary tab. Runs every hour (see setup) and from the Clockdown menu. */
function refreshSummary() {
  const sh = sheet(INSTALLS);
  const rows = sh.getLastRow() > 1 ? sh.getRange(2, 1, sh.getLastRow() - 1, HEADER.length).getValues() : [];
  const now = Date.now();
  const s = summarize(rows, now);
  const out = sheet(SUMMARY);
  out.clear();
  const tz = 'Asia/Kolkata';
  const lines = [
    ['Clockdown versions', '', '', '', '', '', ''],
    ['Updated ' + Utilities.formatDate(new Date(now), tz, 'd MMM yyyy, h:mm a') + ' IST. "Active" = opened or checked in during the last ' + ACTIVE_DAYS + ' days.', '', '', '', '', '', ''],
    ['', '', '', '', '', '', ''],
    ['Active installs', s.activeNow, '', 'Active but on an older version', s.activeOnOlder, 'Installs ever counted', s.installsSeen],
    ['', '', '', '', '', '', ''],
    ['Version', 'Code', 'Active now', 'Installed fresh (this week)', 'Updated to it (this week)', 'Already on it when counting began', 'All ever seen'],
  ];
  for (const v of s.versions) {
    lines.push([(v.name || '?') + (v.version === s.latest ? '  (latest)' : ''), v.version, v.active, v.fresh + ' (' + v.fresh7 + ')', v.updated + ' (' + v.updated7 + ')', v.before, v.seen]);
  }
  if (!s.versions.length) lines.push(['No pings yet.', '', '', '', '', '', '']);
  lines.push(['', '', '', '', '', '', '']);
  lines.push(['A reinstalled or reset phone counts as a new install. ' + s.testInstalls + ' test (debug) installs are left out.', '', '', '', '', '', '']);
  out.getRange(1, 1, lines.length, 7).setValues(lines);
  out.getRange('A1').setFontSize(18).setFontWeight('bold');
  out.getRange('A4:G4').setFontWeight('bold');
  out.getRange('B4').setFontSize(16); out.getRange('E4').setFontSize(16); out.getRange('G4').setFontSize(16);
  out.getRange('A6:G6').setFontWeight('bold').setBackground('#EFEBE2');
  out.setColumnWidth(1, 170); out.setColumnWidths(2, 6, 150);
  out.setFrozenRows(6);
}

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Clockdown').addItem('Refresh summary now', 'refreshSummary').addToUi();
}

/** Run once by hand after pasting this script: makes the two tabs and the hourly summary refresh. */
function setup() {
  sheet(INSTALLS);
  sheet(SUMMARY);
  ScriptApp.getProjectTriggers().filter((t) => t.getHandlerFunction() === 'refreshSummary').forEach((t) => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('refreshSummary').timeBased().everyHours(1).create();
  refreshSummary();
}

function sheet(name) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name, name === SUMMARY ? 0 : ss.getNumSheets());
    if (name === INSTALLS) { sh.appendRow(HEADER); sh.setFrozenRows(1); }
  }
  return sh;
}

if (typeof module !== 'undefined') module.exports = { parsePing, applyPing, summarize, COL, DAY_MS };
