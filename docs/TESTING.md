# Testing BPlay on a Fire TV Stick

Ordered so the things most likely to be broken are checked first. If step 3 fails, nothing after
it matters, and you will have spent five minutes rather than an hour finding that out.

Nothing in this project has run on real hardware yet. The protocol, the TLS, the APK packaging and
the browser page are covered by automated tests; the Fire TV side — hardware decoding, screen
capture, discovery, audio — is not, and cannot be without a device.

---

## Before you start

**On the Fire TV**

1. Same Wi-Fi network as everything you plan to test from. Not a guest network: those usually
   block devices from reaching each other, which breaks every path here.
2. Allow sideloading: *Settings → My Fire TV → Developer Options → Install unknown apps →
   Downloader → On*. On newer Fire OS, unlock Developer Options first via *Settings → My Fire TV →
   About* and clicking *Fire TV Stick* seven times.
3. Turn on **ADB debugging** in the same menu. Strictly optional, but without it a crash is a blank
   screen with no explanation, and with it you get the exact stack trace.
4. Note the address: *Settings → My Fire TV → About → Network*.

**On your computer** (only if you want logs)

```bash
adb connect <fire-tv-ip>:5555      # accept the prompt that appears on the TV
adb logcat -c                      # clear old logs
adb logcat -s BPlayService BPlayCert BPlayWeb BPlayTcp BPlaySession BPlayVideo BPlayAudio \
              BPlayDiscovery BPlayBle BPlayNet BPlayQr BPlayMirror AndroidRuntime
```

Leave that running in a second window for the whole session. `AndroidRuntime` is in the list
because that is where a crash appears.

---

## 1 · Install

Open **Downloader** on the Fire TV and enter:

```
https://github.com/singhbaljeet2404/Bplay/releases/latest/download/bplay-firetv.apk
```

| Expected | If not |
| --- | --- |
| Downloads ~2.8 MB, offers to install | 404 means the repo went private again |
| Installs without a parser error | A parse error means a truncated download — retry |
| **BPlay Mirror appears on the Fire TV home row** | Missing here but present in Settings → Applications means the leanback launcher entry is wrong |

With ADB instead: `adb install -r bplay-firetv.apk`.

## 2 · First launch — the highest-risk step

This is where a Fire OS 5 incompatibility would show up, as an immediate crash back to the home
screen. Open the app.

**Expect:** a dark screen titled BPlay Mirror, showing *Starting…* and then, within a few seconds,
an address and a four-digit PIN.

The first launch is slower than later ones — it generates an RSA key for HTTPS. Up to ~10 seconds
on an older stick is normal. Later launches should be immediate.

| What you see | What it means |
| --- | --- |
| Address like `https://192.168.x.x:8443` and a PIN | Working. Continue. |
| *No network — connect this Fire TV to Wi-Fi* | The stick has no address, or only a VPN interface |
| Stuck on *Starting…* past 30 seconds | Key generation is wedged — get the log |
| *Can't start:* with a message | Read the message; the log has the exception |
| Drops back to the Fire TV home screen | A crash. `AndroidRuntime` in the log has the reason. |

In the log you should see, roughly in this order:

```
BPlayCert     Generating a TLS certificate for [192.168.x.x]
BPlayTcp      Listening on tcp/7100
BPlayDiscovery Advertising as "Fire TV"
BPlayService  Ready at 192.168.x.x (cert AA:BB:...)
```

Also check: the **QR code** renders on the right, and pressing the remote's direction keys moves a
visible highlight between the buttons along the bottom. A TV app that cannot be navigated by remote
is unusable regardless of whether mirroring works.

## 3 · Browser from a laptop — the most likely path to work

On a computer on the same Wi-Fi, open the `https://` address the TV is showing.

1. The browser warns the connection is not private. **Expected** — choose *Advanced*, then
   *Proceed*. If your browser offers no way through, the certificate's SAN is wrong; send me the
   fingerprint the TV shows under Help.
2. The page should load, and the heading should name your TV (it fetches that from the TV).
3. Enter the PIN, leave *This screen* selected, press **Start mirroring**, pick a window or screen.

| Check | Good |
| --- | --- |
| Picture appears on the TV | Within a second or two of pressing Start |
| Latency | Move a window in circles: the TV should trail by a fraction of a second, not seconds |
| Shape | Correct proportions with black bars where needed — never stretched |
| Audio | Tick *Send audio*, play something, and use *Share tab audio* in Chrome's picker |
| Stats | The page's fps and Mbps update once a second and the Dropped counter stays near zero |
| Stop | Press Stop; the TV returns to the BPlay home screen, not a black screen |

Scanning the TV's QR code with a phone should open the same page **with the PIN already filled in**.

## 4 · Refusals — these should fail, cleanly

| Test | Expected |
| --- | --- |
| Enter a wrong PIN | *Wrong PIN* on the sending device; the TV ignores it |
| Start a second device while the first is mirroring | *Another device is already mirroring* |
| Open `https://<tv-ip>:8443` from a phone on mobile data | Does not connect — different network |

## 5 · Android phone

Install `bplay-sender.apk` from the same release, then open **BPlay Sender**.

| Check | Good | If it fails |
| --- | --- | --- |
| The TV appears in the list by itself | Within a few seconds | mDNS is blocked — type the address instead, which must always work |
| Start mirroring | Android's *Start recording?* dialog, then the phone's screen on the TV | — |
| Rotate the phone | The TV follows, letterboxed, without a long freeze | — |
| Audio (Android 10+) | Play music; it comes out of the TV | Many apps opt out of capture; try a different one before reporting it |
| Press Home on the phone | Mirroring continues | — |
| Stop from the notification | The TV returns to its home screen | — |

## 6 · iPhone or iPad

Open the TV's address in Safari and accept the warning once.

*This screen* is greyed out with *Not possible on iPhone or iPad* — **that is correct**, not a bug.
No iOS browser can capture a screen.

| Check | Good |
| --- | --- |
| **Camera** → Start | Live camera on the TV |
| *Switch camera* | Flips lens without dropping the session |
| **Photos & video** → pick several photos | First photo on the TV; *Previous*/*Next* step through them |
| Pick a video | Plays on the TV **with sound**, silent on the phone |

The Web Audio fallback that carries that sound has never run on real Safari; it is the most likely
thing here to misbehave.

## 7 · Leave it running

Short tests hide the failures that matter most.

- **Ten minutes of continuous mirroring.** Latency should not creep upward. If it does, frames are
  queueing somewhere they should be dropped.
- **Walk out of Wi-Fi range, or turn off the sender's Wi-Fi.** Within ~15 seconds the TV should
  return to its home screen, ready for a new connection — not freeze on the last frame.
- **Reboot the Fire TV, reopen the app, and reload the page on a computer that already accepted the
  certificate.** There should be **no second certificate warning**: the key is cached. A second
  warning means the cache is not surviving reboots.
- **Each quality setting** (480p / 720p / 1080p) via the TV's Quality button.
- **Turn the PIN off** on the TV and confirm a sender connects without one.

## 8 · Things that should fail

Confirm these behave as documented, so they are not reported as bugs later:

- **Netflix, Disney+, Prime Video** mirror as a **black screen**. Enforced by the OS; no app can
  change it.
- **Bluetooth alone** never carries video. It only hands the phone the TV's address and PIN.

---

## If something breaks

Send:

1. Which step number.
2. What the TV screen showed.
3. The sending device and OS, and the browser if relevant.
4. The log:

```bash
adb logcat -d -s BPlayService BPlayCert BPlayWeb BPlayTcp BPlaySession BPlayVideo BPlayAudio \
                 BPlayDiscovery BPlayBle BPlayNet BPlayMirror AndroidRuntime > bplay-log.txt
```

For a browser problem, the JavaScript console from the sending device is worth more than the TV's
log.

### Quick triage

| Symptom | Most likely cause |
| --- | --- |
| App crashes on launch | An API newer than Fire OS 5 — `AndroidRuntime` names it |
| Stuck on *Starting…* | RSA key generation, or no usable network interface |
| Browser will not proceed past the warning | Certificate SAN missing the address you used |
| Page loads, Start does nothing | WebCodecs missing or refusing the profile — check the browser console |
| Connects, then black screen on the TV | Decoder never got a keyframe, or `MediaCodec` refused the profile — `BPlayVideo` |
| Video fine, no sound | Expected on iOS and Android 9 and older; otherwise `BPlayAudio` |
| Latency grows over minutes | Queue not draining — `BPlayVideo` logs when it drops a backlog |
| Phone app finds no TV | mDNS blocked by the network; typing the address should still work |
