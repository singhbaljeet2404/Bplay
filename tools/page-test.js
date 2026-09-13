/*
 * Loads the sender page in a real browser and checks it behaves, including a simulated iPhone
 * where screen capture does not exist.
 *
 * Everything here runs against the exact assets that ship inside the APK. The page is the only
 * part of this project a user reaches without installing anything, so a JavaScript error in it
 * would otherwise surface for the first time on someone's phone, in front of a television.
 *
 * Usage: node tools/page-test.js
 *   PLAYWRIGHT_CHROMIUM=/path/to/chrome  use an already-installed browser
 */
const { chromium } = require('playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', 'firetv-receiver', 'src', 'main', 'assets', 'web');
const TYPES = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  if (url === '/info') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ name: 'Living Room Fire TV', version: '1.0.0', pinRequired: true }));
    return;
  }
  const file = path.join(ROOT, url === '/' ? 'index.html' : url);
  if (!file.startsWith(ROOT) || !fs.existsSync(file)) { res.writeHead(404); res.end('no'); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  res.end(fs.readFileSync(file));
});

let failures = 0;
function check(name, ok, detail) {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail && !ok ? ' :: ' + detail : ''}`);
  if (!ok) failures++;
}

(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://localhost:${server.address().port}`;

  // PLAYWRIGHT_CHROMIUM lets a preinstalled browser be used instead of a downloaded one.
  const browser = await chromium.launch({
    executablePath: process.env.PLAYWRIGHT_CHROMIUM || undefined,
    args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  });

  // ---- 1. desktop: screen capture available -----------------------------
  console.log('\n== Desktop browser ==');
  let ctx = await browser.newContext();
  let page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('favicon')) errors.push(m.text());
  });

  await page.goto(`${base}/index.html?pin=4821`, { waitUntil: 'networkidle' });

  check('page loads with no JavaScript errors', errors.length === 0, errors.join(' | '));
  check('secure context (localhost)', await page.evaluate(() => window.isSecureContext));
  check('unsupported panel hidden', await page.isHidden('#panel-unsupported'));
  check('setup panel visible', await page.isVisible('#panel-setup'));
  check('PIN prefilled from the QR link', (await page.inputValue('#pin')) === '4821');
  check('TV name read from /info',
    (await page.textContent('#tv-name')) === 'Living Room Fire TV');
  check('screen selected by default',
    (await page.getAttribute('#source-screen', 'aria-checked')) === 'true');
  check('file picker hidden until chosen', await page.isHidden('#file-field'));

  await page.click('#source-files');
  check('choosing Photos & video reveals the picker', await page.isVisible('#file-field'));
  check('files now selected',
    (await page.getAttribute('#source-files', 'aria-checked')) === 'true');
  await page.click('#source-camera');
  check('camera selectable',
    (await page.getAttribute('#source-camera', 'aria-checked')) === 'true');
  check('start button relabelled for non-screen sources',
    (await page.textContent('#start')).trim() === 'Start');

  // Picking files without choosing any must be refused, not crash.
  await page.click('#source-files');
  await page.click('#start');
  await page.waitForTimeout(300);
  check('empty file selection reports a clear error',
    (await page.textContent('#setup-error')).includes('Choose a photo or video first'));
  check('still no uncaught errors after that', errors.length === 0, errors.join(' | '));
  await ctx.close();

  // ---- 2. simulated iPhone: no getDisplayMedia --------------------------
  console.log('\n== Simulated iPhone (no screen capture API) ==');
  ctx = await browser.newContext({
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 '
      + '(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
    viewport: { width: 390, height: 844 },
  });
  page = await ctx.newPage();
  const iosErrors = [];
  page.on('pageerror', (e) => iosErrors.push(String(e)));
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('favicon')) iosErrors.push(m.text());
  });
  // Remove the one API iOS does not have.
  // getDisplayMedia lives on MediaDevices.prototype, not on the instance, so it has to be
  // removed there to reproduce what an iPhone actually presents.
  await page.addInitScript(() => {
    if (window.MediaDevices) delete MediaDevices.prototype.getDisplayMedia;
  });
  await page.goto(`${base}/index.html`, { waitUntil: 'networkidle' });

  check('page loads with no JavaScript errors', iosErrors.length === 0, iosErrors.join(' | '));
  check('does not show the dead-end "unsupported" panel',
    await page.isHidden('#panel-unsupported'));
  check('setup panel still usable', await page.isVisible('#panel-setup'));
  check('screen source disabled', await page.isDisabled('#source-screen'));
  check('screen source explains why',
    (await page.textContent('#source-screen')).includes('Not possible on iPhone'));
  check('falls back to camera as the default',
    (await page.getAttribute('#source-camera', 'aria-checked')) === 'true');
  check('camera is available', !(await page.isDisabled('#source-camera')));
  check('photos & video available', !(await page.isDisabled('#source-files')));

  await page.click('#source-files');
  check('iPhone can reach the file picker', await page.isVisible('#file-field'));
  const accept = await page.getAttribute('#files', 'accept');
  check('picker accepts photos and video', accept === 'image/*,video/*', accept);

  // No horizontal overflow at phone width.
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('no horizontal scroll at 390px wide', overflow <= 0, `overflow ${overflow}px`);
  await ctx.close();

  await browser.close();
  server.close();
  console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
