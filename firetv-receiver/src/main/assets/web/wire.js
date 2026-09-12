/*
 * The BPlay wire format, in JavaScript.
 *
 * This is a second implementation of protocol/src/main/java/com/bplay/protocol -- Params, Packet
 * and HandshakeCodec -- and the two have to agree byte for byte. It lives in its own file so it
 * can be loaded outside a browser and checked against the Java encoder; see tools/wire-check.js.
 */
(function (root) {
  'use strict';

  const PROTOCOL_VERSION = 1;
  const MAGIC = [0x42, 0x50, 0x4c, 0x59]; // "BPLY"
  const HEADER_SIZE = 14;

  const TYPE = {
    VIDEO_CONFIG: 1,
    VIDEO: 2,
    AUDIO_CONFIG: 3,
    AUDIO: 4,
    PING: 5,
    BYE: 6,
    META: 7,
  };

  const FLAG_KEYFRAME = 0x01;

  function escapeValue(text) {
    return String(text).replace(/([\\=])/g, '\\$1').replace(/\n/g, '\\n');
  }

  function encodeParams(fields) {
    const lines = Object.keys(fields)
      .filter((key) => fields[key] !== undefined && fields[key] !== null)
      .map((key) => escapeValue(key) + '=' + escapeValue(fields[key]));
    return new TextEncoder().encode(lines.join('\n'));
  }

  function decodeParams(bytes) {
    const text = new TextDecoder().decode(bytes);
    const out = {};
    let key = null;
    let token = '';
    let escaped = false;
    for (const char of text) {
      if (escaped) {
        token += char === 'n' ? '\n' : char;
        escaped = false;
      } else if (char === '\\') {
        escaped = true;
      } else if (char === '=' && key === null) {
        key = token;
        token = '';
      } else if (char === '\n') {
        if (key !== null) out[key] = token;
        key = null;
        token = '';
      } else {
        token += char;
      }
    }
    if (key !== null) out[key] = token;
    return out;
  }

  function buildHandshake(fields) {
    const body = encodeParams(fields);
    const out = new Uint8Array(7 + body.length);
    out.set(MAGIC, 0);
    out[4] = PROTOCOL_VERSION;
    out[5] = (body.length >> 8) & 0xff;
    out[6] = body.length & 0xff;
    out.set(body, 7);
    return out;
  }

  function parseHandshakeResponse(buffer) {
    const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
    if (bytes.length < 3) throw new Error('Truncated handshake response');
    const length = (bytes[1] << 8) | bytes[2];
    return {
      status: bytes[0],
      params: decodeParams(bytes.subarray(3, 3 + length)),
    };
  }

  function buildPacket(type, flags, timestampUs, payload) {
    const body = payload instanceof Uint8Array ? payload : new Uint8Array(payload);
    const out = new Uint8Array(HEADER_SIZE + body.length);
    const view = new DataView(out.buffer);
    view.setUint8(0, type);
    view.setUint8(1, flags);
    view.setUint32(2, body.length);
    // Microsecond timestamps pass 2^32 after about 70 minutes; BigInt keeps them exact.
    view.setBigUint64(6, BigInt(Math.max(0, Math.round(timestampUs))));
    out.set(body, HEADER_SIZE);
    return out;
  }

  const api = {
    PROTOCOL_VERSION,
    HEADER_SIZE,
    TYPE,
    FLAG_KEYFRAME,
    encodeParams,
    decodeParams,
    buildHandshake,
    parseHandshakeResponse,
    buildPacket,
  };

  root.BPlayWire = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
}(typeof globalThis !== 'undefined' ? globalThis : this));
