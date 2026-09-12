# BPlay Sender for iPhone and iPad

iOS is the one platform where mirroring cannot be done from a web page. Safari has no
`getDisplayMedia`, and no iOS browser does — that is an Apple restriction, not a BPlay one. The
only route Apple provides for full-device mirroring is a **Broadcast Upload Extension**, which has
to ship inside an app.

There is no App Store build of this, because putting it there would cost money and this project is
free. Instead the source is here and you build it yourself. With a free Apple ID that takes about
ten minutes and costs nothing; the app then runs on your own devices for seven days before needing
a re-install. A paid Apple Developer account ($99/year) extends that to a year.

## What you need

- A Mac with Xcode 15 or newer
- An iPhone or iPad running iOS 14 or newer
- A USB cable
- An Apple ID (a free one is fine)

## Build it

1. **Create the app.** In Xcode: *File → New → Project → iOS → App*.
   - Product Name: `BPlaySender`
   - Interface: **SwiftUI**, Language: **Swift**
   - Organization Identifier: anything you like, e.g. `com.yourname`

2. **Create the broadcast extension.** *File → New → Target → iOS → Broadcast Upload Extension*.
   - Product Name: `BPlayBroadcast`
   - **Uncheck** "Include UI Extension" — it is not needed.
   - When Xcode offers to activate the new scheme, choose Cancel (keep the app's scheme).

3. **Add an App Group** so the two targets can share the TV's address.
   Select the project, then for **both** targets: *Signing & Capabilities → + Capability →
   App Groups*, and add a group named `group.com.bplay.sender`.

4. **Copy in the sources**, replacing the placeholder files Xcode generated:

   | From this repo | Add to target(s) |
   | --- | --- |
   | `Shared/BPlayWire.swift` | BPlaySender **and** BPlayBroadcast |
   | `Shared/MirrorClient.swift` | BPlaySender **and** BPlayBroadcast |
   | `BPlaySender/BPlaySenderApp.swift` | BPlaySender |
   | `BPlaySender/ContentView.swift` | BPlaySender |
   | `BPlayBroadcast/SampleHandler.swift` | BPlayBroadcast (replaces the generated one) |

   The two `Shared/` files must have **both** targets ticked in the File Inspector's Target
   Membership. That is the step people miss, and it produces "cannot find BPlayWire in scope".

5. **Match the extension's bundle identifier.** `ContentView.swift` names the extension as
   `com.bplay.sender.broadcast`. Either set the BPlayBroadcast target's Bundle Identifier to
   exactly that, or edit `preferredExtension` in `ContentView.swift` to match whatever Xcode
   generated (it will be `<your app id>.BPlayBroadcast`).

   If the App Group above uses a different name, change the `suiteName` in both
   `ContentView.swift` and `SampleHandler.swift` to match.

6. **Sign and run.** Select your device, pick your Apple ID under *Signing & Capabilities →
   Team* for both targets, and press Run. On the phone, approve the developer certificate under
   *Settings → General → VPN & Device Management*.

## Use it

1. Open **BPlay Mirror** on the Fire TV and note the address and PIN.
2. Open **BPlay Sender** on the iPhone, type them in.
3. Tap the broadcast button, choose **BPlay**, then **Start Broadcast**.
4. To stop: tap the red bar at the top of the screen, or use the same button again.

Once set up you can skip the app entirely: press and hold the screen-recording button in Control
Centre and pick BPlay from the list.

## Notes

- **Audio.** The extension receives app audio from ReplayKit but does not currently encode it, so
  iOS mirroring is video-only for now. Microphone samples are deliberately ignored — mirroring a
  screen should not quietly broadcast the room.
- **Memory.** Broadcast extensions are killed at 50 MB. Everything here encodes in place and sends
  immediately rather than buffering, which is why the code looks the way it does.
- **DRM.** Netflix, Disney+ and similar apps show a black screen when recorded. That is enforced
  by iOS, and no screen mirroring app can work around it.
