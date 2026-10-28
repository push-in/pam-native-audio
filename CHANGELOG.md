# Changelog

## 0.3.0 - 2026-10-28

- Voice recorder moved out of the PAM Native core (1.46): `AudioRecorder`,
  `AudioRecording` and `AudioRecordingProgress` are now
  `Pam\Native\Audio\*`, with the same `audio-recorder` native module and
  wire payloads on Android and iOS. The plugin declares `RECORD_AUDIO` and
  `NSMicrophoneUsageDescription`. Requires `pushinbr/pam-native` ^1.46 (the
  plugin API `WatchChannel`).

## 0.2.1 - 2026-10-05

- Fix: start the event long-poll only after `play` succeeds. On Android the
  player is created on the main looper, so polling `next` immediately failed
  with "Player not found" and no state, progress or end event reached PHP
  (the UI stayed in loading while audio played).

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
