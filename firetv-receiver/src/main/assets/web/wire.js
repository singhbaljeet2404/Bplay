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
    // Native file playback: the receiver decodes the file itself and pulls it a range at a time.
    MEDIA_OFFER: 8,
    MEDIA_REQUEST: 9,
    MEDIA_DATA: 10,
    MEDIA_END: 11,
    MEDIA_CONTROL: 12,
    MEDIA_STATE: 13,
  };

  const FLAG_KEYFRAME = 0x01;
  const FLAG_LAST_CHUNK = 0x02;

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

  /** Reads a packet the receiver sent back. Mirrors Packet.parse on the Java side. */
  function parsePacket(buffer) {
    const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
    if (bytes.length < HEADER_SIZE) throw new Error('Runt packet');
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const length = view.getUint32(2);
    if (length !== bytes.length - HEADER_SIZE) {
      throw new Error('Declared length ' + length + ' != actual ' + (bytes.length - HEADER_SIZE));
    }
    return {
      type: bytes[0],
      flags: bytes[1],
      timestampUs: view.getBigUint64(6),
      payload: bytes.subarray(HEADER_SIZE),
    };
  }

  /** The MEDIA_DATA payload: request id, absolute offset, then the bytes. */
  function buildMediaChunk(requestId, offset, data) {
    const out = new Uint8Array(12 + data.length);
    const view = new DataView(out.buffer);
    view.setUint32(0, requestId);
    view.setBigUint64(4, BigInt(offset));
    out.set(data, 12);
    return out;
  }

  const api = {
    PROTOCOL_VERSION,
    HEADER_SIZE,
    TYPE,
    FLAG_KEYFRAME,
    FLAG_LAST_CHUNK,
    parsePacket,
    buildMediaChunk,
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
