# Changelog

## 0.1.0 - 2026-10-05

- Add headless `AudioPlayer` on ExoPlayer (Media3 1.10) with sandbox paths and
  cached https sources, audio focus and becoming-noisy handling.
- Add native `queue()` auto-advance, `PlaybackRate` presets, seek, skip, volume
  and a single current player (`AudioPlayer::current()`, `stopAll()`).
- Add `AudioRoute::Auto` proximity routing to the earpiece with the voice stream,
  communication mode, proximity screen-off wake lock and route events.
- Push progress (coalesced native ticker), state, item, route, end and failure
  events through the module event channel.
