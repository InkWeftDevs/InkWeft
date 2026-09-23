/* InkWeft S1 sample — built-artifact self-check.
   Parses the generated index.html, checks the inline script, and verifies that
   every element id the UI reaches for actually exists in the markup. A missing id
   would only surface as a runtime TypeError in a browser, which is exactly the
   class of bug a sample must not ship.

   Run:  node tests/check-build.js
*/
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');

const problems = [];

/* 1. structure */
if (!/^<!--/.test(html)) problems.push('missing generated-file banner');
if (!/<html lang="zh-CN">/.test(html)) problems.push('missing html lang');
if (html.includes('__STYLES__') || html.includes('__SCRIPTS__')) problems.push('placeholder left unreplaced');

/* 2. the inline script must be valid JavaScript */
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) problems.push('no inline <script> block found');
else {
  try {
    new vm.Script(scriptMatch[1], { filename: 'index.inline.js' });
  } catch (e) {
    problems.push('inline script does not parse: ' + e.message);
  }
}

/* 3. no third-party or network references */
const external = [
  [/<script[^>]+src=/i, 'external script tag'],
  [/<link[^>]+rel=["']?stylesheet/i, 'external stylesheet'],
  [/https?:\/\/(?!127\.0\.0\.1)/i, 'absolute URL']
];
for (const [re, label] of external) {
  if (re.test(html)) problems.push('contains ' + label + ' (the sample must be offline-only)');
}

/* 4. every id the UI dereferences must exist in the markup */
const ids = new Set();
const idRe = /id="([A-Za-z0-9_-]+)"/g;
let m;
while ((m = idRe.exec(html))) ids.add(m[1]);

const used = new Set();
const callRe = /el\(['"]([A-Za-z0-9_-]+)['"]\)/g;
while ((m = callRe.exec(scriptMatch ? scriptMatch[1] : ''))) used.add(m[1]);

const missing = [...used].filter((id) => !ids.has(id));
if (missing.length) problems.push('element ids referenced but not in markup: ' + missing.join(', '));

/* 5. module concatenation order must be what the UI expects */
const order = ['00-core.js', '10-vault.js', '20-document.js', '30-commands.js',
               '40-store.js', '50-export.js', '60-seed.js', '70-ui.js'];
let last = -1;
for (const name of order) {
  const at = html.indexOf('/* ===== ' + name);
  if (at < 0) problems.push('module not inlined: ' + name);
  else if (at < last) problems.push('module out of order: ' + name);
  else last = at;
}

/* 6. the honest-disclosure strings must survive into the artifact */
for (const phrase of ['NOT_RUN', 'EXACT_SYNTHETIC', '不建卡', 'PDF 引擎']) {
  if (!html.includes(phrase)) problems.push('disclosure phrase missing from the build: ' + phrase);
}

const stats = {
  htmlBytes: Buffer.byteLength(html),
  inlineScriptBytes: scriptMatch ? Buffer.byteLength(scriptMatch[1]) : 0,
  elementIds: ids.size,
  referencedIds: used.size,
  modules: order.length,
  assets: fs.readdirSync(path.join(ROOT, 'assets')).map((f) => ({
    name: f, bytes: fs.statSync(path.join(ROOT, 'assets', f)).size
  }))
};

if (problems.length) {
  console.error('BUILD CHECK FAILED');
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log('BUILD CHECK PASSED');
console.log(JSON.stringify(stats, null, 2));
