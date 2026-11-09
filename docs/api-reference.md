# PAM Native Audio: API reference

> Moved out of the README; nothing was removed.

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
