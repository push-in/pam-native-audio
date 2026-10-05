# Changelog

## 0.2.0 - 2026-10-05

- iOS: `AudioPlayer` on `AVQueuePlayer` with the Android contract: native
  queue and skip, rate with pitch preserved, volume, seek, coalesced progress,
  state/item/route/end/failure events, interruption and route-loss pauses,
  proximity earpiece routing (`AVAudioSession` + `UIDevice` proximity) and a
  64 MiB LRU cache for remote audio.
- XCTest mirror of the Android suite (`ios/Tests`). Uncompiled on iOS; needs
  device validation.

## 0.1.0 - 2026-10-05

- Add headless `AudioPlayer` on ExoPlayer (Media3 1.10) with sandbox paths and
  cached https sources, audio focus and becoming-noisy handling.
- Add native `queue()` auto-advance, `PlaybackRate` presets, seek, skip, volume
  and a single current player (`AudioPlayer::current()`, `stopAll()`).
- Add `AudioRoute::Auto` proximity routing to the earpiece with the voice stream,
  communication mode, proximity screen-off wake lock and route events.
- Push progress (coalesced native ticker), state, item, route, end and failure
  events through the module event channel.
