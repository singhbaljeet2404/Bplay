# Architecture

## The constraint that shapes everything

A Fire TV Stick is an Android device without Google Play Services. That single fact rules out the
obvious answer — Google Cast — and rules out anything else that assumes a Google-certified device.
What it *does* have is a hardware H.264 decoder, a normal network stack, and the ability to run a
sideloaded APK.

So BPlay is built around the smallest thing every platform can agree on: **raw H.264 access units
in Annex-B form**, with no container.

```
  Android sender    MediaCodec ──┐
  iOS extension     VideoToolbox ├─► H.264 Annex-B ─┬─► TCP :7100 ────────┐
  Browser           WebCodecs  ──┘                  └─► WebSocket :8443 ──┤
                                                                          ▼
                                                        MirrorSession ─► MediaCodec ─► Surface
```

Android's `MediaCodec`, Apple's `VideoToolbox` and a browser's `WebCodecs` encoder can each be
configured to emit exactly this. That is why the receiver has one decoder and not three, and why
every path gets hardware decoding rather than a software fallback on the slowest device involved.

## Why three sender paths instead of one

| Path | Covers | Why it has to exist |
| --- | --- | --- |
| **Browser over WebSocket** | Windows, macOS, Linux, ChromeOS, Android | Nothing to install. This is the path most people will use. |
| **Native TCP** | Android, iOS | `getDisplayMedia` captures a screen only while the browser is open; a native app keeps mirroring when you switch apps, and skips a canvas copy on platforms that need one. |
| **Nothing** | iOS via browser | Safari has no screen-capture API, and iOS forbids other engines. There is no browser path on iOS, only the extension. |

## Why the TV serves HTTPS

Not for secrecy — for capability. Browsers expose `getDisplayMedia()` only in a
[secure context](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts), and
`http://192.168.1.42:8080` is not one. Without TLS the browser sender could not capture a single
frame.

No public certificate authority will issue a certificate for a private LAN address, so the TV
generates its own on first run (`CertificateStore`, using the DER encoder in
`protocol/.../tls/X509SelfSigner.java`) and caches it. The user clicks through the browser warning
once per device.

Two details that matter more than they look:

- **The certificate must carry subjectAltName entries.** Chrome rejects a certificate with no SAN
  outright — not even offering the "proceed anyway" escape hatch.
- **The key is cached, not regenerated per boot.** Otherwise the user would face that warning every
  single time. It is only regenerated when the TV gains an address the old certificate does not
  cover, and old addresses are kept so a returning DHCP lease still works.

## Why Bluetooth carries pairing data and not video

Usable mirroring needs 2–8 Mbit/s sustained. Bluetooth Classic manages 1–2 Mbit/s in practice and
BLE far less. The gap is not a tuning problem, it is an order of magnitude.

What Bluetooth *is* good at is delivering a few bytes to a nearby device without any network at
all. So the TV advertises an 8-byte beacon — 4 bytes of IPv4 address, 2 of PIN, 2 of port — and the
Android sender scans for it. The user never reads an address off the screen. Video then flows over
Wi-Fi like everything else.

This also covers the case mDNS cannot: networks with client isolation, where multicast discovery
finds nothing. Guest and hotel Wi-Fi are usually configured that way.

## Receiver structure

| Class | Responsibility |
| --- | --- |
| `MirrorService` | Foreground service. Owns the servers, the advertising, and the single active session. |
| `TcpStreamServer` | Accepts native senders. Handshake, PIN check, then packets. |
| `WebServer` | NanoHTTPD over TLS: serves the browser sender from assets and carries its WebSocket. |
| `MirrorSession` | One connected device. Routes packets to the decoders. |
| `VideoDecoder` | `MediaCodec` → `Surface`. Drops backlog rather than accumulating latency. |
| `AudioPlayer` | `MediaCodec` → `AudioTrack`. Every failure degrades to silence, never to a dead session. |
| `CertificateStore` | Generates and caches the TLS identity. |
| `Discovery` / `BleBeacon` | mDNS advertising and the Bluetooth pairing beacon. |
| `MainActivity` / `MirrorActivity` | The TV UI and the full-screen playback surface. |

### Two ordering problems worth knowing about

**Video arrives before there is anywhere to put it.** The sender connects and starts streaming
immediately, but the surface only exists once `MirrorActivity` has been launched and laid out. So
`VideoDecoder` waits for the surface rather than the other way round, and then waits again for the
next keyframe — feeding a decoder a P-frame it has no reference for produces garbage or an error.
Senders emit a keyframe every second, so the wait is short.

**Not every sender sends codec-specific data.** Android's encoder reports SPS/PPS as a separate
config buffer, but a browser encoding in Annex-B mode reports no description at all and inlines
SPS/PPS ahead of each keyframe instead. `MediaCodec` needs it up front to configure. `AnnexB`
recovers it from the first keyframe when a sender does not send it explicitly.

## Latency budget

Roughly, on a healthy 5 GHz network:

| Stage | Cost |
| --- | --- |
| Capture and encode | 15–40 ms |
| Wi-Fi | 5–30 ms |
| Decode and render | 20–60 ms |
| **Total** | **100–200 ms** |

The design choices that protect this: `TCP_NODELAY` on every socket, `latencyMode: 'realtime'` and
no B-frames in the encoders, immediate rendering rather than timestamp-scheduled playback, and
bounded queues everywhere that drop their backlog instead of growing. Dropping frames is always
the right call in a mirror — a frame arriving late is worse than useless, because it delays every
frame behind it.

## Deliberate limits

- **One sender at a time.** There is one screen. Silently replacing whoever is on it would be a
  hostile thing for a stranger on the same Wi-Fi to be able to do.
- **No STUN, TURN or relay.** Same network, always. Nothing to configure, nothing to leak.
- **No analytics, no accounts, no network calls off the LAN.**
