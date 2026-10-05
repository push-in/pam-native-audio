# PAM Native Audio

Headless native audio playback for [PAM Native](https://github.com/push-in/pam-native)
applications, built for chat voice notes and short media.

- **ExoPlayer (Media3)** with audio focus, "becoming noisy" pause (headphones
  unplugged) and a 64 MiB LRU cache for remote files.
- **Native queue** — `queue()` advances to the next voice note without a PHP
  round trip, even if PHP is busy.
- **Playback rate** — 0.5× to 2× presets with pitch preserved.
- **Proximity earpiece routing** — `AudioRoute::Auto` moves playback to the
  earpiece (voice stream, screen off) while the phone is at the ear, and back to
  the speaker when it is lowered; headsets are left alone.
- **Pushed events** — progress (native ticker, coalesced), state, queue item,
  route, end and failure arrive through the module event channel; PHP never polls.
- **One current player** — starting a player stops the previous one.

Android API 26+. iOS playback is not shipped in 0.1 (calls fail with a typed
message).

## Install

```bash
pam composer require pushinbr/pam-native-audio
```

Requires `pushinbr/pam-native` `>=1.0.35 <2.0.0`. Declared permissions:
`MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK` (plus network access).

## Play a voice note

```php
use Pam\Native\Audio\{AudioPlayer, AudioRoute, PlaybackProgress, PlaybackRate, PlaybackState};

$player = AudioPlayer::make('voice/8f1c.m4a')           // sandbox-relative path or https URL
    ->rate(PlaybackRate::X1_5)
    ->route(AudioRoute::Auto)
    ->onProgress(fn (PlaybackProgress $p) => $this->setState(['progress' => $p->fraction()]))
    ->onStateChange(fn (PlaybackState $s) => $this->setState(['playing' => $s === PlaybackState::Playing]))
    ->onEnd(fn () => $this->setState(['progress' => 0]))
    ->play();

AudioPlayer::current()?->pause();
AudioPlayer::current()?->resume();
AudioPlayer::current()?->seek(12_000);
AudioPlayer::current()?->rate(AudioPlayer::current()->playbackRate()->nextVoiceNoteRate()); // 1× → 1.5× → 2×
AudioPlayer::stopAll();
```

## Auto-advance through consecutive voice notes

```php
AudioPlayer::make($notes[$i]->path)
    ->queue(...array_map(fn ($n) => $n->path, array_slice($notes, $i + 1)))
    ->onItemChange(fn (int $index, string $source) => $this->highlight($i + $index))
    ->onEnd(fn () => $this->highlight(null))
    ->play();
```

## API

| Method | Notes |
| --- | --- |
| `make(string $source, ?string $id = null)` | `https://…` or a relative path inside the PAM file sandbox |
| `queue(string ...$sources)` | Up to 200 items, before `play()` |
| `rate(PlaybackRate)` / `route(AudioRoute)` / `volume(float)` | Before or during playback |
| `startAt(int $ms)` / `progressInterval(int $ms)` | Before `play()`; interval 50–5000 ms |
| `onProgress` / `onStateChange` / `onItemChange` / `onRouteChange` / `onEnd` / `onError` | Pushed natively |
| `play()` / `pause()` / `resume()` / `toggle()` / `seek()` / `skipTo()` / `stop()` | |
| `AudioPlayer::current()` / `AudioPlayer::stopAll()` | |

## Tests

```bash
pam tests/run.php
cd android && ANDROID_SERIAL=emulator-5558 ../../../pam-native/android/gradlew -p . connectedDebugAndroidTest
```

The instrumented suite plays generated WAV files through ExoPlayer: queue
auto-advance with progress and end events, pause/resume/seek/rate on the live
player, single current player, proximity earpiece routing (voice stream, audio
mode, route events) and failure handling.

## License

Apache-2.0
