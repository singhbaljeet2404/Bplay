# The BPlay wire protocol (BMP/1)

Implemented three times — Java (`protocol/src/main/java/com/bplay/protocol`), JavaScript
(`firetv-receiver/src/main/assets/web/wire.js`) and Swift (`ios-sender/Shared/BPlayWire.swift`) —
so the three are held together by shared conformance vectors in `tools/wire-vectors.txt`. Change
the format and all three plus the vectors change together, or CI fails.

## Transports

| Port | Protocol | Used by |
| --- | --- | --- |
| 7100 | TCP | Android and iOS sender apps |
| 8443 | HTTPS + WebSocket (`/stream`) | Browser sender |

Packets are byte-identical on both. A WebSocket binary message contains exactly one packet.

## Discovery

mDNS service type `_bplay._tcp.`, port 7100, with TXT records:

| Key | Meaning |
| --- | --- |
| `v` | Protocol version |
| `web` | HTTPS port |
| `pin` | `1` if a PIN is required |
| `name` | The TV's display name |

Bluetooth LE beacon, service UUID `0000b91a-0000-1000-8000-00805f9b34fb`, 8 bytes of service data:

```
[0..3] IPv4 address   [4..5] PIN as big-endian u16 (0xFFFF = no PIN)   [6..7] stream port
```

## Handshake

Sender to receiver:

```
"BPLY"  version:u8  length:u16  Params
```

Receiver to sender:

```
status:u8  length:u16  Params
```

| Status | Meaning |
| --- | --- |
| 0 | OK |
| 1 | Wrong PIN |
| 2 | Busy — another device is mirroring |
| 3 | Version mismatch |

Request keys: `pin`, `name`, `platform`, `width`, `height`, `fps`, `vcodec`, and when audio will
be sent, `acodec`, `asamplerate`, `achannels`.

Response keys on success: `name`, `maxWidth`, `maxHeight`, `maxBitrate`. On failure: `error`, a
message meant to be shown to the user as-is.

PINs are compared in constant time. A four-digit PIN on a home network is weak enough without also
being brute-forceable one character at a time by timing the reply.

## Packets

```
offset  size  field
0       1     type
1       1     flags
2       4     payload length (unsigned, big-endian)
6       8     presentation timestamp, microseconds
14      n     payload
```

| Type | Name | Payload |
| --- | --- | --- |
| 1 | `VIDEO_CONFIG` | Annex-B SPS + PPS |
| 2 | `VIDEO` | One Annex-B access unit |
| 3 | `AUDIO_CONFIG` | Raw codec-specific data, **or** a `Params` blob containing a `codec` key |
| 4 | `AUDIO` | One audio access unit |
| 5 | `PING` | Empty |
| 6 | `BYE` | Empty |
| 7 | `META` | `Params`: `width`, `height` after a rotation or resize |

Flags: bit 0 means keyframe.

Timestamps are microseconds and pass 2³² after about 70 minutes, which is why the field is 64 bits
and why the JavaScript implementation uses `BigInt` rather than `setUint32`.

Packets are capped at 8 MB so a malformed or hostile sender cannot make the TV allocate an
arbitrary buffer.

### The two shapes of `AUDIO_CONFIG`

Android and iOS senders have real codec-specific data (an AAC `AudioSpecificConfig`) and send the
raw bytes. A browser producing Opus through WebCodecs has none — there is no identification header
in a raw Opus stream — so it sends a `Params` description instead and the receiver synthesises the
19-byte `OpusHead` that `MediaCodec` requires. The presence of a `codec` key is what distinguishes
them.

## Params

UTF-8 `key=value` lines separated by `\n`. Backslash escapes `\\`, `=` and `\n`, so a value may
contain any of them — device names really do contain `=` and newlines when someone renames a phone.

It replaces JSON here deliberately: this is the one place where four codebases must agree
byte-for-byte, and a format this small can be reimplemented correctly in a dozen lines anywhere.
