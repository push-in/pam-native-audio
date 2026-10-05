<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Headless native audio player (ExoPlayer on Android).
 *
 * One player is current at a time: playing a new one stops the previous, the
 * usual behaviour for voice notes. A queue advances natively (no PHP round
 * trip between items). Progress, state, item and end events are pushed through
 * the module event channel at the configured interval.
 */
final class AudioPlayer
{
    public const string MODULE = 'audio-player';

    private static ?self $current = null;

    /** @var list<string> */
    private array $sources;
    private PlaybackRate $rate = PlaybackRate::X1;
    private AudioRoute $route = AudioRoute::Auto;
    private float $volume = 1.0;
    private int $startAtMillis = 0;
    private int $progressIntervalMillis = 250;
    private bool $started = false;
    private bool $stopped = false;
    private PlaybackState $state = PlaybackState::Idle;
    private ?PlaybackProgress $progress = null;
    private int $index = 0;

    /** @var array<string, list<Closure>> */
    private array $handlers = [];

    private function __construct(public readonly string $id, string $source)
    {
        $this->sources = [self::source($source)];
    }

    /** @param string $source https URL or a path relative to the PAM file sandbox (e.g. `voice/123.m4a`). */
    public static function make(string $source, ?string $id = null): self
    {
        $id ??= 'ap-'.bin2hex(random_bytes(6));
        if (preg_match('/^[A-Za-z0-9_-]{1,64}$/D', $id) !== 1) {
            throw new InvalidArgumentException('Invalid player id.');
        }

        return new self($id, $source);
    }

    public static function current(): ?self
    {
        return self::$current;
    }

    public static function stopAll(): void
    {
        self::$current?->stop();
    }

    /** Appends sources played after the first one, advancing automatically. */
    public function queue(string ...$sources): self
    {
        $this->assertConfigurable();
        if (count($this->sources) + count($sources) > 200) {
            throw new InvalidArgumentException('A queue holds at most 200 items.');
        }
        foreach ($sources as $source) {
            $this->sources[] = self::source($source);
        }

        return $this;
    }

    /** Applies immediately when already playing. */
    public function rate(PlaybackRate $rate): self
    {
        $this->rate = $rate;
        if ($this->started && !$this->stopped) {
            $this->send('setRate', ['rate' => $rate->factor()]);
        }

        return $this;
    }

    /** Applies immediately when already playing. */
    public function route(AudioRoute $route): self
    {
        $this->route = $route;
        if ($this->started && !$this->stopped) {
            $this->send('setRoute', ['route' => $route->value]);
        }

        return $this;
    }

    public function volume(float $volume): self
    {
        $this->volume = max(0.0, min(1.0, $volume));
        if ($this->started && !$this->stopped) {
            $this->send('setVolume', ['volume' => $this->volume]);
        }

        return $this;
    }

    public function startAt(int $millis): self
    {
        $this->assertConfigurable();
        $this->startAtMillis = max(0, $millis);

        return $this;
    }

    /** Native progress event interval (50–5000 ms). */
    public function progressInterval(int $millis): self
    {
        $this->assertConfigurable();
        $this->progressIntervalMillis = max(50, min(5_000, $millis));

        return $this;
    }

    /** @param Closure(PlaybackProgress, AudioPlayer): void $handler */
    public function onProgress(Closure $handler): self
    {
        return $this->listen('progress', $handler);
    }

    /** Fires once when the last queued item finishes. @param Closure(AudioPlayer): void $handler */
    public function onEnd(Closure $handler): self
    {
        return $this->listen('end', $handler);
    }

    /** @param Closure(PlaybackState, AudioPlayer): void $handler */
    public function onStateChange(Closure $handler): self
    {
        return $this->listen('state', $handler);
    }

    /** @param Closure(int, string, AudioPlayer): void $handler Receives the new index and source. */
    public function onItemChange(Closure $handler): self
    {
        return $this->listen('item', $handler);
    }

    /** @param Closure(AudioRoute, AudioPlayer): void $handler Reports Speaker/Earpiece switches. */
    public function onRouteChange(Closure $handler): self
    {
        return $this->listen('route', $handler);
    }

    /** @param Closure(string, AudioPlayer): void $handler */
    public function onError(Closure $handler): self
    {
        return $this->listen('error', $handler);
    }

    public function play(): self
    {
        if ($this->stopped) {
            throw new LogicException('This player was stopped; create a new one.');
        }
        if ($this->started) {
            return $this->resume();
        }
        if (self::$current !== null && self::$current !== $this) {
            self::$current->stop();
        }
        self::$current = $this;
        $this->started = true;
        NativeModules::call(self::MODULE, 'play', [
            'playerId' => $this->id,
            'sourcesJson' => json_encode($this->sources, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
            'rate' => $this->rate->factor(),
            'route' => $this->route->value,
            'volume' => $this->volume,
            'startAt' => $this->startAtMillis,
            'progressInterval' => $this->progressIntervalMillis,
        ], function (NativeModuleResult $result): void {
            if (!$result->succeeded()) {
                $this->fail($result->message());

                return;
            }
            // Android creates the player on the main looper after `play`
            // returns, so the event long-poll must start only once the player
            // exists; polling earlier failed with "Player not found" and no
            // state/progress/end event ever reached PHP.
            if (!$this->stopped) {
                $this->next();
            }
        });

        return $this;
    }

    public function pause(): self
    {
        return $this->send('pause');
    }

    public function resume(): self
    {
        return $this->send('resume');
    }

    public function toggle(): self
    {
        return $this->state === PlaybackState::Playing ? $this->pause() : $this->resume();
    }

    public function seek(int $millis): self
    {
        return $this->send('seek', ['position' => max(0, $millis)]);
    }

    public function skipTo(int $index): self
    {
        if ($index < 0 || $index >= count($this->sources)) {
            throw new InvalidArgumentException('Queue index out of range.');
        }

        return $this->send('skipTo', ['index' => $index]);
    }

    public function stop(): void
    {
        if ($this->stopped) {
            return;
        }
        $this->stopped = true;
        if ($this->started) {
            NativeModules::call(self::MODULE, 'stop', ['playerId' => $this->id], static fn (NativeModuleResult $r): null => null);
        }
        if (self::$current === $this) {
            self::$current = null;
        }
    }

    public function state(): PlaybackState
    {
        return $this->state;
    }

    public function progress(): ?PlaybackProgress
    {
        return $this->progress;
    }

    public function index(): int
    {
        return $this->index;
    }

    public function currentSource(): string
    {
        return $this->sources[$this->index] ?? $this->sources[0];
    }

    /** @return list<string> */
    public function sources(): array
    {
        return $this->sources;
    }

    public function playbackRate(): PlaybackRate
    {
        return $this->rate;
    }

    public function isStopped(): bool
    {
        return $this->stopped;
    }

    /** @internal @param array<string, string|int|float|bool> $values */
    public function dispatch(array $values): void
    {
        $kind = AudioEventKind::tryFrom((int) ($values['kind'] ?? 0));
        if ($kind === null) {
            return;
        }
        if (isset($values['index'])) {
            $this->index = max(0, min(count($this->sources) - 1, (int) $values['index']));
        }
        switch ($kind) {
            case AudioEventKind::Progress:
                $this->progress = PlaybackProgress::fromWire($values);
                $this->emit('progress', $this->progress, $this);
                break;
            case AudioEventKind::State:
                $this->state = PlaybackState::tryFrom((int) ($values['state'] ?? 0)) ?? $this->state;
                $this->emit('state', $this->state, $this);
                break;
            case AudioEventKind::ItemChanged:
                $this->emit('item', $this->index, $this->currentSource(), $this);
                break;
            case AudioEventKind::Ended:
                $this->state = PlaybackState::Ended;
                if (self::$current === $this) {
                    self::$current = null;
                }
                $this->stopped = true;
                $this->emit('end', $this);
                break;
            case AudioEventKind::Failure:
                $this->fail((string) ($values['message'] ?? 'Playback failed'));
                break;
            case AudioEventKind::Route:
                $route = AudioRoute::tryFrom((int) ($values['route'] ?? 0));
                if ($route !== null) {
                    $this->emit('route', $route, $this);
                }
                break;
        }
    }

    private function fail(string $message): void
    {
        $this->state = PlaybackState::Failed;
        $this->emit('error', $message, $this);
    }

    private function next(): void
    {
        NativeModules::call(self::MODULE, 'next', ['playerId' => $this->id], function (NativeModuleResult $result): void {
            if (!$result->succeeded()) {
                return;
            }
            $this->dispatch($result->values());
            if (!$this->stopped) {
                $this->next();
            }
        });
    }

    /** @param array<string, string|int|float|bool> $values */
    private function send(string $method, array $values = []): self
    {
        if (!$this->started || $this->stopped) {
            throw new LogicException('The player is not playing.');
        }
        NativeModules::call(self::MODULE, $method, ['playerId' => $this->id, ...$values], function (NativeModuleResult $result) use ($method): void {
            if (!$result->succeeded()) {
                $this->fail("{$method}: {$result->message()}");
            }
        });

        return $this;
    }

    private function listen(string $event, Closure $handler): self
    {
        $this->handlers[$event][] = $handler;

        return $this;
    }

    private function emit(string $event, mixed ...$arguments): void
    {
        foreach ($this->handlers[$event] ?? [] as $handler) {
            $handler(...$arguments);
        }
    }

    private function assertConfigurable(): void
    {
        if ($this->started) {
            throw new LogicException('Configure the queue before play().');
        }
    }

    private static function source(string $source): string
    {
        if (preg_match('#^https?://\S+$#i', $source) === 1 && strlen($source) <= 4096) {
            return $source;
        }
        if (
            $source === ''
            || strlen($source) > 1024
            || str_starts_with($source, '/')
            || str_contains($source, '://')
            || str_contains($source, '\\')
            || preg_match('#(^|/)\.\.(/|$)#', $source) === 1
        ) {
            throw new InvalidArgumentException('Audio sources must be http(s) URLs or relative sandbox paths.');
        }

        return $source;
    }
}
