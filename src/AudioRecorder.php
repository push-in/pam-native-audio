<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

use function is_array;
use function is_int;

final class AudioRecorder
{
    private static int $nextSubscription = 1;

    /** @var array<int, array{callback: Closure, failure: ?Closure, interrupted: ?Closure, native: ?int}> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /** @param Closure(): void $callback @param null|Closure(string): void $failure */
    public static function start(Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'audio-recorder',
            'start',
            [],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    self::fail($failure, $result->payload);

                    return;
                }
                $callback();
            },
        );
    }

    /** @param Closure(AudioRecording): void $callback @param null|Closure(string): void $failure */
    public static function stop(Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'audio-recorder',
            'stop',
            [],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    self::fail($failure, $result->payload);

                    return;
                }
                $callback(self::recording(Wire::decodeMap($result->payload)));
            },
        );
    }

    /** @param Closure(): void $callback @param null|Closure(string): void $failure */
    public static function cancel(Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'audio-recorder',
            'cancel',
            [],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    self::fail($failure, $result->payload);

                    return;
                }
                $callback();
            },
        );
    }

    /** @param Closure(): void $callback @param null|Closure(string): void $failure */
    public static function discard(string $uri, Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'audio-recorder',
            'discard',
            ['uri' => $uri],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    self::fail($failure, $result->payload);

                    return;
                }
                $callback();
            },
        );
    }

    /**
     * Progress while recording. When the system interrupts the recording (an
     * incoming call or another app takes the microphone), the recorder stops
     * and keeps the partial file: `$interrupted` receives it (null when it was
     * too short to keep) and the watch ends. `stop()` also returns that
     * partial recording, and `cancel()` deletes it.
     *
     * @param Closure(AudioRecordingProgress): void $callback
     * @param null|Closure(string): void $failure
     * @param null|Closure(?AudioRecording): void $interrupted
     */
    public static function watch(Closure $callback, int $intervalMs = 100, ?Closure $failure = null, ?Closure $interrupted = null): int
    {
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = [
            'callback' => $callback,
            'failure' => $failure,
            'interrupted' => $interrupted,
            'native' => null,
        ];
        NativeModules::call(
            'audio-recorder',
            'watch',
            ['intervalMs' => max(50, min(1_000, $intervalMs))],
            static function ($result) use ($subscription): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    $failure = self::$subscriptions[$subscription]['failure'] ?? null;
                    unset(self::$subscriptions[$subscription]);
                    self::fail($failure, $result->payload);

                    return;
                }
                $native = (int) (Wire::decodeMap($result->payload)['subscription'] ?? 0);
                if (!isset(self::$subscriptions[$subscription])) {
                    NativeModules::call(
                        'audio-recorder',
                        'unwatch',
                        ['subscription' => $native],
                        static fn ($result): null => null,
                    );

                    return;
                }
                self::$subscriptions[$subscription]['native'] = $native;
                self::next($subscription);
            },
        );

        return $subscription;
    }

    public static function unwatch(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        unset(self::$subscriptions[$subscription]);
        if (is_array($watch) && is_int($watch['native'])) {
            NativeModules::call(
                'audio-recorder',
                'unwatch',
                ['subscription' => $watch['native']],
                static fn ($result): null => null,
            );
        }
    }

    private static function next(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        if (!is_array($watch) || !is_int($watch['native'])) {
            return;
        }
        NativeModules::call(
            'audio-recorder',
            'next',
            ['subscription' => $watch['native']],
            static function ($result) use ($subscription): void {
                $watch = self::$subscriptions[$subscription] ?? null;
                if (!is_array($watch)) {
                    return;
                }
                if ($result->status === ModuleResultStatus::Failure) {
                    $failure = $watch['failure'];
                    unset(self::$subscriptions[$subscription]);

                    self::fail($failure, $result->payload);

                    return;
                }
                $values = Wire::decodeMap($result->payload);
                if ((bool) ($values['interrupted'] ?? false)) {
                    self::unwatch($subscription);
                    $watch['interrupted']?->__invoke(isset($values['uri']) ? self::recording($values) : null);

                    return;
                }
                ($watch['callback'])(new AudioRecordingProgress(
                    durationMs: max(0, (int) ($values['durationMs'] ?? 0)),
                    amplitude: max(0.0, min(1.0, (float) ($values['amplitude'] ?? 0.0))),
                ));
                self::next($subscription);
            },
        );
    }

    /** @param array<string, mixed> $values */
    private static function recording(array $values): AudioRecording
    {
        return new AudioRecording(
            uri: (string) ($values['uri'] ?? ''),
            fileName: (string) ($values['fileName'] ?? 'voice.m4a'),
            mimeType: (string) ($values['mimeType'] ?? 'audio/mp4'),
            durationMs: max(0, (int) ($values['durationMs'] ?? 0)),
            size: max(0, (int) ($values['size'] ?? 0)),
            relativePath: (string) ($values['relativePath'] ?? ''),
        );
    }

    /** @param null|Closure(string): void $failure */
    private static function fail(?Closure $failure, string $message): void
    {
        if ($failure !== null) {
            $failure($message);

            return;
        }

        throw new RuntimeException($message);
    }
}
