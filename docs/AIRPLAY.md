# AirPlay, and why BPlay does not implement it

The most common request for a Fire TV mirroring app is: *let my iPhone AirPlay to it directly,
with nothing installed on the phone.* It is the right thing to want — AirPlay is built into iOS,
it needs no app, and it just works. This page explains why BPlay does not provide it, and what
does.

## The blocker is DRM, not effort

AirPlay screen mirroring encrypts the video stream. The keys come from one of two exchanges:

- **FairPlay** (`fp-setup`), Apple's own DRM handshake, on the legacy path.
- **Pair-verify** using HomeKit-style keys, on AirPlay 2 — which requires certificates issued
  under Apple's **MFi** licensing programme.

A receiver has to complete one of them or it never gets a decryptable frame. There is no
configuration that turns it off, and no public specification that describes it.

That leaves two ways to build an AirPlay receiver:

1. **Licence it from Apple** through MFi. This is what the commercial receivers do. It costs
   money and involves a hardware certificate.
2. **Use reverse-engineered FairPlay code** lifted from Apple's binaries. This is what every
   open-source AirPlay receiver does, without exception.

BPlay will not ship the second. It is circumvention code for a content-protection system,
derived from Apple's own binaries, and publishing it in this repository would put that exposure
on whoever owns the repository. That is the entire reason there is no free, legitimate AirPlay
receiver for the Fire TV Stick — not that nobody has got round to it.

## What actually works

| Route | App on the iPhone? | Free? | Fire TV **Stick**? |
| --- | --- | --- | --- |
| Fire TV **Omni / 4-Series televisions** — AirPlay 2 built in | No | Yes | No: those are TVs |
| A licensed receiver app **on the Fire TV** (AirScreen, AirReceiver) | No | Partly | Yes |
| **BPlay's browser page** — camera, photos, video | No | Yes | Yes |
| **BPlay's iOS sender app** — full screen mirroring | Built from source | Yes | Yes |

Amazon added AirPlay 2 to its own Fire TV Omni and 4-Series televisions in 2022. It has never come
to the Fire TV Stick, Stick 4K Max, or Cube, and Amazon has not announced plans for it.

The second row is worth understanding properly: those apps install on the **Fire TV**, not on the
iPhone. Your iPhone keeps using its own AirPlay button with nothing installed, which is usually
what people actually mean by the request. They are licensed, so they can do what this project
cannot. AirScreen has a free, ad-supported tier; AirReceiver is a few dollars. They coexist with
BPlay perfectly well — use AirPlay for iOS and BPlay for everything else.

## What BPlay gives iOS instead

Safari has no screen-capture API, and Apple requires every iOS browser to use Safari's engine, so
no browser on iOS can mirror a screen. But Safari does have camera access and a file picker, so
the sender page offers an iPhone or iPad, with nothing installed:

- **Camera** — live, either lens, straight to the TV.
- **Photos** — pick several and step through them with Previous/Next on the phone.
- **Video** — plays on the TV with sound, controlled from the phone.

Open the `https://` address shown on the TV, or scan the QR code. Full screen mirroring still
needs the [iOS sender app](../ios-sender/README.md), which is free but has to be built once in
Xcode.
