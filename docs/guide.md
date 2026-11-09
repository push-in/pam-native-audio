# PAM Native Audio: playback guide

> Moved out of the README; nothing was removed.

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
[`example/`](../example).

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
