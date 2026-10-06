<!-- pam:product-page:start -->
<div align="center">

# PAM Native Audio

**Voice notes that play, queue and route like a messenger.**

Headless native audio playback for PAM Native: ExoPlayer and AVQueuePlayer with a native queue, pitch-preserving speed, proximity earpiece routing and pushed progress events.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-audio?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-audio)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](https://push-in.github.io/pam-docs/packages/native-audio/) · [Quick start](#quick-start) · [API reference](#api-reference) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-audio/issues)**

</div>

---

## Why PAM Native Audio

Chat voice notes need more than a `<MediaPlayer>`: the next note must start
the moment the current one ends (even if PHP is busy rendering), speed changes
must keep the voice natural, and holding the phone to the ear must switch to
the earpiece and turn the screen off. This package does all of that natively
and pushes compact events to PHP; PHP never polls.

| | |
| --- | --- |
| **Best for** | Voice notes, podcasts snippets, short audio messages, sound previews |
| **Native path** | Media3 ExoPlayer 1.10 on Android · `AVQueuePlayer` + `AVAudioSession` on iOS |
| **Application model** | Composer package + generated native integration (`audio-player` module) |
| **Design rule** | Headless: no UI bundled; your component renders the bubble and waveform |

## What you can build

- **Voice-note bubbles** with play/pause, seek, elapsed time and a 1× → 1.5× → 2× speed toggle.
- **Auto-advance** through consecutive voice notes with a native queue (up to 200 items).
- **Ear-to-phone playback**: `AudioRoute::Auto` moves playback to the earpiece
  (voice stream, screen off) while the phone is at the ear and back to the
  speaker when it is lowered; wired and Bluetooth headsets are left alone.
- **Cached remote audio**: https sources stream on first play and are kept in
  a 64 MiB LRU cache.

## Quick start

Already have a PAM Native project? Add only this capability:

```bash
pam composer require pushinbr/pam-native-audio
pam doctor --fix
```

```php
use Pam\Native\Audio\{AudioPlayer, PlaybackProgress};

AudioPlayer::make('https://cdn.example.com/voice/8f1c.m4a')
    ->onProgress(function (PlaybackProgress $p): void {
        $this->progress = $p->fraction(); // re-renders the component
    })
    ->play();
```

New to PAM? Follow the **[five-minute PAM Native setup](https://push-in.github.io/pam-docs/native/overview/)** once, then return here. Your application stays a normal Composer project with a committed lockfile.
<!-- pam:product-page:end -->

## Install

```bash
pam composer require pushinbr/pam-native-audio
pam doctor --fix
```

The package is a PAM Native plugin discovered from Composer; nothing is added
to `pam-native.json`. It requires `pushinbr/pam-native` `>=1.0.35 <2.0.0` and
PHP 8.5.

### Android

Merged permissions: `INTERNET`, `ACCESS_NETWORK_STATE` (remote sources),
`MODIFY_AUDIO_SETTINGS` (communication mode for the earpiece) and `WAKE_LOCK`
(proximity screen-off). No runtime permission is needed. Dependencies:
`androidx.media3:media3-exoplayer`, `media3-datasource` and `media3-database`
`1.10.1`. The player requests transient audio focus, pauses when focus is lost
and when headphones are unplugged ("becoming noisy"). Remote audio is cached in
`cacheDir/pam-audio-cache` (64 MiB LRU).

### iOS

Frameworks `AVFoundation` and `CoreMedia`; no Info.plist keys and no usage
strings. The speaker route uses the `playback` category with the `spokenAudio`
mode; the earpiece route uses `playAndRecord` with `voiceChat` and enables
`UIDevice` proximity monitoring (the screen turns off at the ear). Playback
pauses on interruptions (a phone call) and when headphones are unplugged.

The package does not declare the `audio` background mode, so iOS suspends
playback when the app goes to the background unless another plugin (for
example `pam-native-calls`) or your host declares it.

### Sources

A source is either an `http(s)://` URL (≤ 4096 bytes) or a path **relative to
the PAM file sandbox** (≤ 1024 bytes, no leading `/`, no `..`, no `\`, no
scheme): `filesDir/pam-files` on Android, `Application Support/pam-files` on
iOS — the same space as `FileReference::$path`, the core `AudioRecorder` and
`pam-native-media`. A recorder `file://…/pam-files/voice/1.m4a` URI must be
reduced to `voice/1.m4a` before playing it.

## Play a voice note

```php
use Pam\Native\Audio\{AudioPlayer, AudioRoute, PlaybackProgress, PlaybackRate, PlaybackState};

$player = AudioPlayer::make('voice/8f1c.m4a')           // sandbox-relative path or https URL
    ->rate(PlaybackRate::X1_5)
    ->route(AudioRoute::Auto)
    ->progressInterval(250)
    ->onProgress(function (PlaybackProgress $p): void { $this->position = $p->positionMillis; })
    ->onStateChange(function (PlaybackState $s): void { $this->playing = $s === PlaybackState::Playing; })
    ->onEnd(function (): void { $this->position = 0; })
    ->onError(function (string $message): void { $this->error = $message; })
    ->play();

AudioPlayer::current()?->pause();
AudioPlayer::current()?->resume();
AudioPlayer::current()?->seek(12_000);
AudioPlayer::current()?->rate(AudioPlayer::current()->playbackRate()->nextVoiceNoteRate()); // 1× → 1.5× → 2×
AudioPlayer::stopAll();
```

Event handlers run on the PHP worker; writing component properties from them
re-renders only that component (PAM Native 1.2.1+ memoization).

## Auto-advance through consecutive voice notes

```php
AudioPlayer::make($notes[$i]->path)
    ->queue(...array_map(fn ($n) => $n->path, array_slice($notes, $i + 1)))
    ->onItemChange(fn (int $index, string $source) => $this->highlight($i + $index))
    ->onEnd(fn () => $this->highlight(null))
    ->play();
```

The queue advances natively. `onItemChange` reports the new index (relative to
the first source) and `onEnd` fires once, after the last item.

## A real example: Zé Chat

Zé Chat keeps one app-wide player for voice notes. Tapping a bubble queues the
following received voice notes of the same conversation, the speed is
remembered across notes, and a spinner appears only if the note is still
preparing after 300 ms:

```php
use Pam\Native\Audio\{AudioPlayer, AudioRoute, PlaybackProgress, PlaybackRate, PlaybackState};

final class VoiceNotePlayback
{
    private static ?AudioPlayer $player = null;
    private static PlaybackRate $rate = PlaybackRate::X1;

    public static function start(string $source, array $following, int $startAtMs = 0): void
    {
        self::$player?->stop();
        try {
            self::$player = AudioPlayer::make($source)
                ->queue(...$following)                 // the next notes play without PHP
                ->rate(self::$rate)
                ->route(AudioRoute::Auto)              // earpiece at the ear
                ->progressInterval(250)
                ->startAt($startAtMs)                  // tap on the waveform before playing
                ->onProgress(static fn (PlaybackProgress $p, AudioPlayer $player) => self::progressed($player, $p))
                ->onStateChange(static fn (PlaybackState $s, AudioPlayer $player) => self::stateChanged($player, $s))
                ->onItemChange(static fn (int $index, string $_source, AudioPlayer $player) => self::moveTo($player, $index))
                ->onEnd(static fn (AudioPlayer $player) => self::finished($player))
                ->onError(static function (string $message, AudioPlayer $player): void {
                    // A live rate change rejected while the note is preparing is not a playback failure.
                    if (!str_starts_with($message, 'setRate:')) {
                        self::failed($player);
                    }
                });
            self::$player->play();
        } catch (Throwable) {
            self::failed(self::$player);
        }
    }

    public static function cycleRate(): void
    {
        self::$rate = self::$rate->nextVoiceNoteRate();
        if (self::$player !== null && !self::$player->isStopped()) {
            self::$player->rate(self::$rate);          // applied live
        }
    }
}
```

Handlers compare the `AudioPlayer` argument with the current player so late
events of a replaced player are ignored. A runnable minimal app is in
[`example/`](example).

## API reference

All classes live in `Pam\Native\Audio`.

### `AudioPlayer`

One player is current at a time: `play()` on a new player stops the previous one.

| Method | Description |
| --- | --- |
| `make(string $source, ?string $id = null): self` | Creates a player. `$id` matches `[A-Za-z0-9_-]{1,64}` (random `ap-…` by default). |
| `current(): ?self` | The playing (or paused) player, if any. |
| `stopAll(): void` | Stops the current player. |
| `queue(string ...$sources): self` | Appends sources; at most 200 in total. Before `play()`. |
| `rate(PlaybackRate $rate): self` | Speed, pitch preserved. Applied live when playing. |
| `route(AudioRoute $route): self` | Output route. Applied live when playing. |
| `volume(float $volume): self` | Clamped to 0–1. Applied live when playing. |
| `startAt(int $millis): self` | Start position of the first item. Before `play()`. |
| `progressInterval(int $millis): self` | Progress event interval, clamped to 50–5000 ms (default 250). Before `play()`. |
| `onProgress(Closure(PlaybackProgress, AudioPlayer))` | Coalesced native ticker. |
| `onStateChange(Closure(PlaybackState, AudioPlayer))` | State transitions. |
| `onItemChange(Closure(int, string, AudioPlayer))` | Queue index and source. |
| `onRouteChange(Closure(AudioRoute, AudioPlayer))` | `Speaker`/`Earpiece` switches. |
| `onEnd(Closure(AudioPlayer))` | Once, after the last item. The player is then stopped. |
| `onError(Closure(string, AudioPlayer))` | Playback failures and rejected commands (`"pause: …"`, `"setRate: …"`). |
| `play(): self` | Starts playback (or resumes a started player). |
| `pause()`, `resume()`, `toggle()`: `self` | `toggle()` pauses when `Playing`, resumes otherwise. |
| `seek(int $millis): self` | Seeks the current item. |
| `skipTo(int $index): self` | Jumps to a queue item. |
| `stop(): void` | Stops and releases the native player; idempotent. |
| `state(): PlaybackState`, `progress(): ?PlaybackProgress`, `index(): int` | Last known values. |
| `currentSource(): string`, `sources(): list<string>`, `playbackRate(): PlaybackRate`, `isStopped(): bool` | Inspection. |
| `$id` | Readonly player id. |
| `MODULE` | `'audio-player'`. |

`dispatch()` is `@internal`.

### `PlaybackProgress` (readonly)

`positionMillis`, `durationMillis` (0 until known), `bufferedMillis`, `index`,
`fraction(): float` (0–1).

### Enums (int-backed)

| Enum | Cases |
| --- | --- |
| `PlaybackState` | `Idle = 1`, `Buffering = 2`, `Playing = 3`, `Paused = 4`, `Ended = 5`, `Failed = 6` |
| `PlaybackRate` | `X0_5 = 1`, `X0_75 = 2`, `X1 = 3`, `X1_25 = 4`, `X1_5 = 5`, `X1_75 = 6`, `X2 = 7`; `factor(): float`, `nextVoiceNoteRate(): self` (1× → 1.5× → 2× → 1×) |
| `AudioRoute` | `Auto = 1` (proximity), `Speaker = 2`, `Earpiece = 3` |
| `AudioEventKind` | `Progress = 1`, `State = 2`, `ItemChanged = 3`, `Ended = 4`, `Failure = 5`, `Route = 6` (wire events) |

### Errors

- `InvalidArgumentException`: invalid id, a source that is neither an https URL
  nor a safe relative path, more than 200 queued items, `skipTo()` out of range.
- `LogicException`: `queue()`, `startAt()` or `progressInterval()` after
  `play()` ("Configure the queue before play()"); `pause()`, `resume()`,
  `seek()`, `skipTo()`, `rate()`/`route()`/`volume()` commands on a player that
  is not started or already stopped ("The player is not playing"); `play()` on
  a stopped player ("create a new one").
- Native failures (unreadable file, network error, decoder error, "Player … not
  found") arrive through `onError()` and set the state to `Failed`.

## Limits and troubleshooting

- **Nothing plays from a recording:** pass the sandbox-relative path, not an
  absolute path or `file://` URI.
- **State and progress never arrive (0.2.0):** upgrade to 0.2.1; the event
  long-poll now starts only after `play` succeeds.
- **Playback stops in the background on iOS:** declare the `audio` background mode.
- **The earpiece never engages:** `AudioRoute::Auto` needs a proximity sensor
  and is ignored while a headset is connected; force `AudioRoute::Earpiece` to test.
- **iOS validation:** the iOS implementation mirrors the Android suite
  (`ios/Tests/AudioPlayerTests.swift`) but has not been validated on a device yet.

## Compatibility

| `pushinbr/pam-native-audio` | `pushinbr/pam-native` | Android | iOS |
| --- | --- | --- | --- |
| 0.2.x | `>=1.0.35 <2.0.0` (tested with 1.14.x) | API 26+ | 15+ |
| 0.1.x | `>=1.0.35 <2.0.0` | API 26+ | Not supported |

## Tests

```bash
pam tests/run.php
cd android && ../../pam-native/android/gradlew -p . connectedDebugAndroidTest
```

The instrumented suite plays generated WAV files through ExoPlayer: queue
auto-advance with progress and end events, pause/resume/seek/rate on the live
player, single current player, proximity earpiece routing (voice stream, audio
mode, route events) and failure handling. `ios/Tests/AudioPlayerTests.swift`
mirrors it with XCTest.

## License

Apache-2.0
