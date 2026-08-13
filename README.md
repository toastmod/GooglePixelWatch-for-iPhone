# Google Pixel Watch for iPhone

A proof-of-concept Wear OS app that bridges an iPhone's notifications and
Now Playing media controls to a Google Pixel Watch over Bluetooth LE, using
Apple's private-but-documented **ANCS** (Apple Notification Center Service)
and **AMS** (Apple Media Service) protocols. The Pixel Watch has no official
iPhone support — this exists because a $400 smartwatch bricking itself the
moment you leave the Android ecosystem is silly.

Huge thanks to [loud-at-heart](https://github.com/loud-at-heart) for the AMS implementation!

> **Tested on: Google Pixel Watch 2.** All BLE/ANCS/AMS behavior described
> below (including the workarounds for iOS-side quirks) was verified against
> a real Pixel Watch 2 paired to a real iPhone — not an emulator. Other Pixel
> Watch generations may behave differently.

## What it does

- **Notifications (ANCS):** subscribes to the iPhone's Notification Center
  and mirrors incoming notifications (app, title, subtitle, message) as
  native Android notifications on the watch.
- **Now Playing (AMS):** subscribes to the iPhone's current media session
  and shows a live-updating control card — track title/artist, play/pause,
  and skip previous/next — that sends real remote-control commands back to
  the phone.
- **Marquee text:** track title/artist scroll only when they don't fit,
  using Compose's `basicMarquee`, so idle/short text never animates and
  never drains battery for no reason.
- **Self-healing Track metadata:** iOS doesn't always resend the current
  track's title/artist when the watch (re)connects to a song that was
  already playing — a known AMS quirk. This app detects that stuck state
  (player reports "playing" but no track metadata ever arrived) and cycles
  the AMS Entity Update subscription to force iOS to push it again, instead
  of leaving the UI stuck.

## Setup

- You'll need a Pixel Watch that's already been activated by an Android
  phone (the initial Wear OS setup requires Android; after that, this app
  handles the iPhone side).
- In the Pixel Watch's Bluetooth settings, manually pair it with your
  iPhone.
- Open this project in the latest [Android Studio](https://developer.android.com/studio),
  build and install it on your Pixel Watch.
- On your iPhone, accept the prompt asking to share **Notifications** and
  **Media Controls** with the watch.
- Play something on your iPhone and open the app on the watch — the Now
  Playing card should show up with working controls. Text yourself to test
  notifications.

## References

- [Apple Notification Center Service (ANCS) specification](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html)
- Apple Media Service (AMS) — unofficial/reverse-engineered, no public Apple
  spec page; based on community documentation and BLE traffic captures.
- A lot of general BLE knowledge scattered across the internet that's
  impossible to properly cite at this point.
