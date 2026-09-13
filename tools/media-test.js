#!/usr/bin/env node
/*
 * Drives the browser sender's native file playback against a stand-in receiver.
 *
 * This is the path where a mistake is most expensive and least visible: an off-by-one in a byte
 * offset does not throw, it hands the television a file with a hole in it, which surfaces as a
 * video that stutters or refuses to play with nothing in any log to say why. So a real Chromium
 * loads the real page, is given a real file, and every byte it sends back is compared with what
 * that range should contain.
 *
 * Usage: node tools/media-test.js
 *   PLAYWRIGHT_CHROMIUM=/path/to/chrome  use an already-installed browser
 */
'use strict';

const { chromium } = require('playwright-core');
const { WebSocketServer } = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const wire = require(path.join(__dirname, '..', 'firetv-receiver', 'src', 'main', 'assets',
  'web', 'wire.js'));

const ROOT = path.join(__dirname, '..', 'firetv-receiver', 'src', 'main', 'assets', 'web');
const TYPES = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };

let failures = 0;
function check(name, ok, detail) {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && detail ? ' :: ' + detail : ''}`);
  if (!ok) failures++;
}

// A file with no repeating structure, so a misaligned range cannot accidentally match.
const FILE_SIZE = 900 * 1024;
const FILE_BYTES = crypto.randomBytes(FILE_SIZE);

function decodeParams(bytes) {
  return wire.decodeParams(bytes);
}

function main() {
  const server = http.createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url === '/info') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ name: 'Test Fire TV', version: 'test', pinRequired: false }));
      return;
    }
    const file = path.join(ROOT, url === '/' ? 'index.html' : url);
    if (!file.startsWith(ROOT) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    res.end(fs.readFileSync(file));
  });

  const wss = new WebSocketServer({ server, path: '/stream' });

  return new Promise((resolve) => {
    const received = { offer: null, chunks: [], ends: [] };
    let requestsIssued = 0;

    // The ranges a player realistically asks for: the opening, a seek into the middle, and the
    // tail, where a 32-bit offset would be most likely to go wrong on a large file.
    const RANGES = [
      { req: 1, offset: 0, length: 64 * 1024 },
      { req: 2, offset: 300 * 1024 + 7, length: 100 * 1024 },
      { req: 3, offset: FILE_SIZE - 1000, length: 1000 },
    ];

    wss.on('connection', (socket) => {
      socket.binaryType = 'arraybuffer';
      let handshaken = false;

      socket.on('message', (data) => {
        const bytes = new Uint8Array(data);
        if (!handshaken) {
          handshaken = true;
          // Accept, and advertise that this receiver plays files itself.
          const body = wire.encodeParams({
            name: 'Test Fire TV', maxWidth: 1920, maxHeight: 1080,
            maxBitrate: 8000000, media: '1',
          });
          const reply = new Uint8Array(3 + body.length);
          reply[0] = 0;
          reply[1] = (body.length >> 8) & 0xff;
          reply[2] = body.length & 0xff;
          reply.set(body, 3);
          socket.send(reply);
          return;
        }

        const packet = wire.parsePacket(bytes);
        if (packet.type === wire.TYPE.MEDIA_OFFER) {
          received.offer = decodeParams(packet.payload);
          for (const range of RANGES) {
            requestsIssued++;
            socket.send(wire.buildPacket(wire.TYPE.MEDIA_REQUEST, 0, 0, wire.encodeParams({
              req: range.req, id: received.offer.id,
              offset: range.offset, length: range.length,
            })));
          }
        } else if (packet.type === wire.TYPE.MEDIA_DATA) {
          const view = new DataView(packet.payload.buffer, packet.payload.byteOffset,
            packet.payload.byteLength);
          received.chunks.push({
            requestId: view.getUint32(0),
            offset: Number(view.getBigUint64(4)),
            data: Buffer.from(packet.payload.subarray(12)),
            last: (packet.flags & wire.FLAG_LAST_CHUNK) !== 0,
          });
        } else if (packet.type === wire.TYPE.MEDIA_END) {
          received.ends.push(decodeParams(packet.payload));
          if (received.ends.length === RANGES.length) {
            setTimeout(() => resolve({ received, RANGES }), 250);
          }
        }
      });
    });

    server.listen(0, '127.0.0.1', async () => {
      const base = `http://localhost:${server.address().port}`;
      const browser = await chromium.launch({
        executablePath: process.env.PLAYWRIGHT_CHROMIUM || undefined,
        args: ['--no-sandbox'],
      });
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', (e) => errors.push(String(e)));
      page.on('console', (m) => {
        if (m.type() === 'error' && !m.text().includes('favicon')) errors.push(m.text());
      });

      await page.goto(`${base}/index.html`, { waitUntil: 'networkidle' });
      await page.click('#source-files');
      await page.setInputFiles('#files', {
        name: 'Holiday.mp4', mimeType: 'video/mp4', buffer: FILE_BYTES,
      });
      await page.click('#start');

      const result = await new Promise((r) => {
        const done = (value) => r(value);
        const timer = setTimeout(() => done(null), 25000);
        const poll = setInterval(() => {
          if (received.ends.length === RANGES.length) {
            clearTimeout(timer); clearInterval(poll); setTimeout(() => done(true), 300);
          }
        }, 100);
      });

      check('page ran without JavaScript errors', errors.length === 0, errors.join(' | '));
      check('the browser answered every range request', result === true,
        `${received.ends.length} of ${RANGES.length} completed`);

      // Transport controls should be on screen for a video the TV is playing itself.
      if (result === true) {
        check('transport controls shown for native playback',
          await page.isVisible('#transport'));
        check('stats show the file size, not a frame rate',
          (await page.textContent('#stat-fps')) === 'Native');
      }

      await browser.close();
      server.close();
      wss.close();

      verify(received, RANGES);
      console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`);
      process.exit(failures === 0 ? 0 : 1);
    });
  });
}

function verify(received, RANGES) {
  check('the offer described the file', !!received.offer
    && received.offer.name === 'Holiday.mp4'
    && received.offer.mime === 'video/mp4'
    && Number(received.offer.size) === FILE_SIZE,
    JSON.stringify(received.offer));

  for (const range of RANGES) {
    const chunks = received.chunks.filter((c) => c.requestId === range.req)
      .sort((a, b) => a.offset - b.offset);
    const assembled = Buffer.concat(chunks.map((c) => c.data));
    const expected = FILE_BYTES.subarray(range.offset, range.offset + range.length);

    check(`range at ${range.offset} returned ${range.length} bytes`,
      assembled.length === range.length, `got ${assembled.length}`);
    check(`range at ${range.offset} returned the right bytes`,
      assembled.equals(expected),
      assembled.length === expected.length ? 'content differs' : 'length differs');
    check(`range at ${range.offset} reported its absolute offsets`,
      chunks.length > 0 && chunks[0].offset === range.offset,
      chunks.length ? `first chunk claimed ${chunks[0].offset}` : 'no chunks');
    check(`range at ${range.offset} marked its final chunk`,
      chunks.length > 0 && chunks[chunks.length - 1].last);
  }
}

main();
