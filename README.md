# BPlay Mirror

Free, open-source screen mirroring to an **Amazon Fire TV Stick** from Windows, macOS, Linux,
Chromebook, Android, iPhone and iPad.

No account. No subscription. No cloud service. Nothing leaves your own network — the video goes
from your device straight to your TV over Wi-Fi.

---

## Install on the Fire TV

Open the **Downloader** app on your Fire TV and enter this address:

```
https://github.com/singhbaljeet2404/Bplay/releases/latest/download/bplay-firetv.apk
```

Downloader fetches the file and offers to install it. Then open **BPlay Mirror** from the Fire TV
home screen — the address, PIN and a QR code you need are all on that first screen.

<details>
<summary><strong>Fire TV won't let you install it?</strong> Sideloading is off by default.</summary>

- **Newer Fire OS:** Settings → My Fire TV → About → click *Fire TV Stick* seven times →
  go back → Developer Options → *Install unknown apps* → **Downloader** → On
- **Older Fire OS:** Settings → My Fire TV → Developer Options → *Apps from Unknown Sources* → On

If you don't have Downloader yet, it is free in the Fire TV Appstore — search for "Downloader" by
AFTVnews.
</details>

<details>
<summary><strong>That address is long to type with a remote.</strong> Two ways to shorten it.</summary>

Downloader accepts short codes from the AFTVnews URL shortener at **aftv.news**. Paste the address
above into it once, and you get a short code you can type on the remote instead. Those codes belong
to whoever creates them, so this project cannot ship one for you.

Alternatively, type it once on the Fire TV and use **Downloader's bookmark feature** so future
updates are one click.
</details>

---

## Then mirror from anything

| Your device | What to do | Install anything? |
| --- | --- | --- |
| **Windows / macOS / Linux / Chromebook** | Open the `https://` address shown on the TV in Chrome, Edge, Firefox or Safari. Pick a screen, press Start. | **No** |
| **Android phone or tablet** | Install [`bplay-sender.apk`](https://github.com/singhbaljeet2404/Bplay/releases/latest/download/bplay-sender.apk), or just use Chrome and the same address. | Optional |
| **iPhone / iPad** | Build the sender app from [`ios-sender/`](ios-sender/README.md). | Yes — see below |

Scanning the QR code on the TV opens the right address **with the PIN already filled in**.

### The browser will warn you the connection is not private

That is expected, and it is safe. Your Fire TV signs its own certificate, because no certificate
authority on the internet will vouch for a private address like `192.168.1.42`. Choose
**Advanced → Proceed**; your browser remembers it.

The encryption itself is real. HTTPS is not decoration here — browsers refuse to hand any page
the screen-capture API unless it was loaded over a secure connection, so without it the browser
path could not exist at all. The TV shows its certificate fingerprint under **Help** if you want
to check what you trusted.

---

## Honest answers about two things you asked for

### Bluetooth cannot carry a screen

This came up in the original request, so it deserves a straight answer rather than a checkbox in a
feature list.

Screen mirroring at a usable quality needs roughly **2–8 Mbit/s sustained**. Bluetooth Classic
tops out near 1–2 Mbit/s of real throughput and BLE is far below that. There is no encoder setting
that closes that gap — a Bluetooth "screen mirror" would be a slideshow at best. Every app that
claims to mirror a screen over Bluetooth is either using Wi-Fi underneath or showing you still
images.

So BPlay uses Bluetooth for the job it is genuinely good at: **the Fire TV broadcasts its address
and PIN over Bluetooth LE**, and the Android sender picks that up and fills them in. It removes
the "read an IP off the TV and type it on a phone" step, which is the part people actually find
annoying. It matters most on networks that block mDNS — guest and hotel Wi-Fi usually do.

Video always travels over Wi-Fi.

### iPhone and iPad need a build step

Safari has no screen-capture API. Neither does Chrome or Firefox on iOS, because Apple requires
them to use Safari's engine. This is an iOS restriction and no app can route around it.

Apple's only sanctioned route for full-device mirroring is a **Broadcast Upload Extension**, which
must ship inside an app. Putting that on the App Store costs $99/year, which would make this
project not free. So the complete iOS source is in [`ios-sender/`](ios-sender/README.md) and you
build it yourself — about ten minutes in Xcode with a free Apple ID, at no cost.

The alternative that needs no build at all: **AirPlay to an Apple TV**, or use any other device
from the table above.

---

## How it works

```
  Android app ──┐                                    ┌─► H.264 decoder ─► TV screen
  iOS extension ├─► H.264 Annex-B ─► TCP :7100 ──────┤   (one MediaCodec pipeline,
  Browser ──────┘                   WebSocket :8443 ─┘    shared by every sender)
```

Every sender produces the same thing: raw H.264 access units with no container around them.
Android's `MediaCodec`, iOS's `VideoToolbox` and a browser's `WebCodecs` encoder can all be told to
emit exactly that, so the Fire TV runs **one** decode path no matter where a frame came from, and
hardware decoding works everywhere.

Latency on a decent home network lands around 100–200 ms — fine for slides, browsing, photos and
video, and usable for anything but twitch gaming.

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) ·
[docs/PROTOCOL.md](docs/PROTOCOL.md) · [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)

---

## Building it yourself

```bash
git clone https://github.com/singhbaljeet2404/Bplay.git
cd Bplay
./gradlew assembleRelease
```

You need JDK 17 and an Android SDK with API 34. The APKs land in
`*/build/outputs/apk/release/`. `./gradlew :protocol:test` runs the unit tests, and
`node tools/wire-check.js` verifies the browser sender still encodes bytes the receiver
understands.

Signing is [deliberately public](docs/SIGNING.md) — see that page for why a release key is
committed here on purpose.

### Cutting a release

The install link at the top of this page points at `releases/latest`, so it starts working as soon
as one release exists and keeps working for every release after. Two ways to publish one, both of
which build the APKs from scratch in CI and attach them:

- **From the Actions tab:** *Build → Run workflow*, set **release** to `v1.0.0`, Run. The tag is
  created from the commit that gets built.
- **From a terminal:** `git tag v1.0.0 && git push origin v1.0.0`.

Either way the same workflow runs the tests, checks the browser and receiver still agree on the
wire format, verifies the APK declares a Fire TV launcher and requires no mandatory hardware, and
only then publishes.

---

## What it does and doesn't do

**Works:** whole-screen mirroring with audio from Windows/macOS/Linux (Chrome, Edge, Firefox,
Safari), Android 5.0+, iOS 14+; 480p/720p/1080p at 30 fps; PIN pairing; one device at a time;
Fire TV Stick 2nd gen and newer, Fire TV Cube, Fire TV built into a television.

**Doesn't work, and won't:**

- **Netflix, Disney+, Prime Video and other DRM-protected apps show a black screen.** The
  operating system enforces that on both Android and iOS. No mirroring app can defeat it, and one
  that claims to is lying to you.
- **Bluetooth-only mirroring** — see above.
- **Mirroring across different networks or over the internet.** Same Wi-Fi, by design.
- **Audio from iOS**, for now — iOS mirroring is video-only.
- **Audio from Android 9 and older.** `AudioPlaybackCapture` arrived in Android 10; before that an
  app could not legally record another app's sound.

## Licence

MIT. See [LICENSE](LICENSE).
