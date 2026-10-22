// node test.js — checks the counting logic in Code.gs without any Google services.
const assert = require('assert');
const fs = require('fs');
const m = { exports: {} };
new Function('module', fs.readFileSync(__dirname + '/Code.gs', 'utf8'))(m);
const { parsePing, applyPing, summarize } = m.exports;

const id = (c) => c.repeat(32);
const DAY = 86400000;
const now = 1_800_000_000_000;
const rows = {};
function ping(b, at = now) { const p = parsePing(b); assert(p, 'valid ping ' + JSON.stringify(b)); rows[p.id] = applyPing(rows[p.id] || null, p, at); }

// junk is refused
assert.strictEqual(parsePing({ id: 'not-hex', v: 1 }), null);
assert.strictEqual(parsePing({ id: id('a'), v: -1 }), null);
assert.strictEqual(parsePing(null), null);

ping({ id: id('a'), v: 11, vn: '1.6', fresh: true });                // fresh install on 1.6
ping({ id: id('b'), v: 11, vn: '1.6', fresh: false });               // was on the app before counting began
ping({ id: id('c'), v: 11, vn: '1.6', fresh: true }, now - 3 * DAY);  // fresh on 1.6 ...
ping({ id: id('c'), v: 11, vn: '1.6', fresh: true }, now - 2 * DAY);  // ... pings again (no change) ...
ping({ id: id('c'), v: 12, vn: '1.7', fresh: false });               // ... then updates to 1.7
ping({ id: id('d'), v: 12, vn: '1.7', fresh: true, debug: true });   // test device: left out
ping({ id: id('e'), v: 12, vn: '1.7', fresh: true });                // fresh install on 1.7
ping({ id: id('f'), v: 11, vn: '1.6', fresh: true }, now - 30 * DAY); // installed long ago, never seen since: not active

const s = summarize(Object.values(rows), now);
const v = (code) => s.versions.find((x) => x.version === code);
assert.strictEqual(s.latest, 12);
assert.strictEqual(s.installsSeen, 5);
assert.strictEqual(s.testInstalls, 1);
assert.strictEqual(s.activeNow, 4);
assert.strictEqual(s.activeOnOlder, 2);                      // a and b are active on 1.6
assert.deepStrictEqual([v(12).fresh, v(12).updated, v(12).before, v(12).active], [1, 1, 0, 2]);
assert.deepStrictEqual([v(11).fresh, v(11).updated, v(11).before, v(11).active, v(11).seen], [2, 0, 1, 2, 3]);
assert.strictEqual(v(12).name, '1.7');
console.log('all counting checks pass');
