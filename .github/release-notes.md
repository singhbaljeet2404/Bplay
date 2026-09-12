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
| Windows, Mac, Linux, Chromebook | Open the `https://` address shown on the TV in any modern browser. Nothing to install. |
| Android phone or tablet | Install `bplay-sender.apk` below, or just use Chrome. |
| iPhone or iPad | Build the sender app from `ios-sender/` — Safari cannot capture a screen on iOS. |

## Files

- **bplay-firetv.apk** — the receiver. This is the one that goes on the Fire TV.
- **bplay-sender.apk** — the optional Android sender app, for lower latency than the browser.
