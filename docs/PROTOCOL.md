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
| 8 | `MEDIA_OFFER` | sender → receiver. `id`, `name`, `mime`, `size`, `kind` |
| 9 | `MEDIA_REQUEST` | **receiver → sender.** `req`, `id`, `offset`, `length` |
| 10 | `MEDIA_DATA` | sender → receiver. `MediaChunk`: request id, offset, bytes |
| 11 | `MEDIA_END` | sender → receiver. `req`, optional `error` |
| 12 | `MEDIA_CONTROL` | sender → receiver. `action` of play/pause/seek/stop, `positionMs` |
| 13 | `MEDIA_STATE` | receiver → sender. `positionMs`, `durationMs`, `state` |

Flags: bit 0 means keyframe; bit 1 marks the last chunk of a `MEDIA_DATA` range.

### Native file playback

Mirroring a video means re-encoding it on the phone: quality is lost, the battery drains, and
seeking is impossible because the receiver only ever sees a live stream. So a file can instead be
handed over as itself.

The receiver does not download it first. Uploading a three-gigabyte film would mean minutes of
waiting and somewhere to put it, and a Fire TV Stick has very little room. Instead the receiver's
own media player reads from a loopback HTTP server, and each read it performs becomes a
`MEDIA_REQUEST` back over the connection the sender already has open. Playback starts at once, and
a seek is just a read at a different offset.

This is the only thing that made the receiver talk back mid-session, so it is also the only place
`ReverseChannel` is used. Both transports are bidirectional; the receiver simply had nothing to
say before.

Support is advertised in the handshake response as `media=1`. A sender that does not see it falls
back to re-encoding, so an older receiver still shows the video.

Offsets are 64-bit throughout. A film is comfortably past the point where 32 bits would wrap, and
the failure mode is not an error but a file with a hole in it.

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
