# Troubleshooting

## The TV says "No network"

BPlay needs the Fire TV on Wi-Fi or Ethernet. It does not need internet access — only a local
network — but it does need an address. Check Settings → Network.

## My phone or laptop can't find the TV / the page won't load

In order of how often it turns out to be the cause:

1. **Different networks.** A phone on mobile data cannot reach the TV. Many routers also present
   2.4 GHz and 5 GHz as separate networks; join the one the Fire TV is on.
2. **Guest network.** Guest and hotel Wi-Fi almost always enable *client isolation* (sometimes
   called AP isolation), which blocks devices from talking to each other at all. Nothing running
   on either device can work around that. Use the main network.
3. **The address changed.** DHCP leases expire. Whatever the TV shows right now is correct.
4. **A VPN on the phone or laptop.** Most VPN apps route all traffic away from the local network.
   Turn it off, or exclude local addresses.

The TV's home screen always shows a working address; typing it manually rules out discovery
problems entirely.

## The browser says "Your connection is not private"

Expected. Choose **Advanced → Proceed**. See the explanation in the main README — the short version
is that the TV signs its own certificate because no certificate authority will vouch for a private
address, and the encryption is real regardless.

If your browser gives no "proceed" option at all, it is usually one of:

- **A managed/work device** with a policy blocking certificate exceptions. Use a personal device.
- **HSTS on the domain** — not applicable to a bare IP address, so this is rare.

## "This browser can't capture a screen"

- **On iPhone or iPad:** correct, and unavoidable. No iOS browser has a screen-capture API. Use the
  [iOS sender app](../ios-sender/README.md).
- **On a desktop:** update the browser. The page needs `getDisplayMedia` (all modern browsers) and
  WebCodecs (Chrome/Edge 94+, Safari 16.4+, Firefox 133+).
- **If the address bar shows `http://` not `https://`:** open the `https://` address shown on the
  TV. Screen capture is blocked outside a secure context.

## The picture is black, but the app says it is mirroring

Almost always **DRM**. Netflix, Disney+, Prime Video, Hulu and similar apps deliberately produce a
black frame when the screen is being recorded or mirrored. This is enforced by Android and iOS
below the level any app can reach. Any app claiming otherwise is not doing what it says.

Everything else — browsers, photos, documents, games, video files, YouTube — mirrors fine.

## It's laggy or stuttering

1. **Drop the quality** on the TV (the Quality button) or in the sender. 720p is noticeably more
   forgiving than 1080p.
2. **Prefer 5 GHz Wi-Fi** for both devices. 2.4 GHz is crowded and slow enough to matter here.
3. **Check the Dropped counter** in the browser sender. A rising number means the encoder or the
   network cannot keep up; lowering the quality is the fix.
4. **Move closer to the router**, or check whether anything else is saturating the network.

A Fire TV Stick 2nd gen is meaningfully slower than a 4K Max. On the oldest hardware, 720p is the
realistic target.

## No sound

- **Android 9 or older:** not possible. `AudioPlaybackCapture` arrived in Android 10; before that
  no app could record another app's audio.
- **iOS:** not implemented yet — iOS mirroring is video-only.
- **Browser:** Chrome offers a "Share audio" tick box, but only for a tab or the whole screen on
  some platforms, not for a single window. Firefox and Safari are more limited still.
- **Android 10+:** apps can opt out of being captured, and many music and video apps do. You will
  get silence from those specifically.

## "Another device is already mirroring"

One screen, one sender. Stop the other device, or press Back on the TV remote to end the current
session.

## The PIN isn't accepted

The PIN changes if the app is reinstalled or you press **New PIN**. Use what the TV shows now.
Scanning the QR code fills it in automatically and avoids the problem.

## Installing the APK fails on the Fire TV

- Turn on *Install unknown apps* for Downloader (see the README).
- If you previously installed a build signed with a different key, uninstall it first — Android
  refuses to replace an app with one signed by a different key.
- Make sure the download completed; a truncated file fails to parse.

## Reporting something else

Open an issue with: the Fire TV model, the sending device and OS, the browser if relevant, and what
the TV's screen said. `adb logcat -s BPlayService BPlayVideo BPlayWeb BPlayTcp` on the Fire TV is
the most useful thing you can attach.
