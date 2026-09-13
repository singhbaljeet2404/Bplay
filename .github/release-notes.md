Free, open-source screen mirroring to an Amazon Fire TV Stick. No account, no subscription, no
cloud — everything stays on your own network.

## Install on the Fire TV

Open the **Downloader** app on your Fire TV and enter this address:

```
https://github.com/singhbaljeet2404/Bplay/releases/latest/download/bplay-firetv.apk
```

Downloader will fetch the file and offer to install it. Say yes, then open **BPlay Mirror** from
the Fire TV home screen. Everything you need is on that screen.

> Fire TV blocks sideloading until you allow it: **Settings → My Fire TV → Developer Options →
> Install unknown apps → Downloader → On**. On newer Fire OS builds the option is under
> **Settings → My Fire TV → About**, where you click *Fire TV Stick* seven times first.

## Then mirror from anything

| Device | What to do |
| --- | --- |
| Windows, Mac, Linux, Chromebook | Open the `https://` address shown on the TV in any modern browser. Mirror a screen, share a camera, or cast photos and video. Nothing to install. |
| Android phone or tablet | Install `bplay-sender.apk` below, or just use Chrome. |
| iPhone or iPad | Open the same address in Safari for **camera, photos and video** — nothing to install. Screen mirroring needs the app in `ios-sender/`, because no iOS browser can capture a screen. |

**AirPlay?** Not possible here — it needs Apple's licensing. See `docs/AIRPLAY.md` for what does
work, including licensed receiver apps that install on the Fire TV and leave your iPhone
untouched.

## Files

- **bplay-firetv.apk** — the receiver. This is the one that goes on the Fire TV.
- **bplay-sender.apk** — the optional Android sender app, for lower latency than the browser.
