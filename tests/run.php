<?php

declare(strict_types=1);

$packageAutoload = dirname(__DIR__).'/vendor/autoload.php';
if (is_file($packageAutoload)) {
    require $packageAutoload;
}
$roots = [
    'Pam\\Native\\Audio\\' => dirname(__DIR__).'/src/',
    'Pam\\Native\\Testing\\' => dirname(__DIR__, 2).'/pam-native-testing/src/',
    'Pam\\Native\\' => dirname(__DIR__, 2).'/../pam-native/packages/native/src/',
];
spl_autoload_register(static function (string $class) use ($roots): void {
    foreach ($roots as $prefix => $root) {
        if (str_starts_with($class, $prefix)) {
            $file = $root.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($file)) {
                require $file;
            }

            return;
        }
    }
});

use Pam\Native\Audio\AudioEventKind;
use Pam\Native\Audio\AudioPlayer;
use Pam\Native\Audio\AudioRoute;
use Pam\Native\Audio\PlaybackProgress;
use Pam\Native\Audio\PlaybackRate;
use Pam\Native\Audio\PlaybackState;
use Pam\Native\Internal\Wire;
use Pam\Native\Testing\DispatchMode;
use Pam\Native\Testing\FakeNativeModuleTransport;
use Pam\Native\Testing\NativeTestHarness;

$tests = [];
$test = static function (string $name, Closure $body) use (&$tests): void {
    $tests[$name] = $body;
};
$check = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$throws = static function (string $class, Closure $body) use ($check): void {
    try {
        $body();
    } catch (Throwable $error) {
        $check($error instanceof $class, 'expected '.$class.', got '.$error::class);

        return;
    }
    throw new RuntimeException("expected {$class}");
};
$install = static function (string ...$extra): FakeNativeModuleTransport {
    AudioPlayer::current()?->stop();
    $transport = NativeTestHarness::install();
    foreach (['play', 'stop', 'pause', 'resume', 'seek', 'setRate', 'setRoute', 'skipTo', 'setVolume', ...$extra] as $method) {
        for ($i = 0; $i < 4; $i++) {
            $transport->succeed('audio-player', $method);
        }
    }

    return $transport;
};
$methods = static fn (FakeNativeModuleTransport $t): array => array_map(static fn ($c): string => $c->method, $t->calls());

$test('coded variants are sequential and rates map to factors', static function () use ($check): void {
    foreach ([PlaybackRate::cases(), AudioRoute::cases(), PlaybackState::cases(), AudioEventKind::cases()] as $cases) {
        $values = array_map(static fn ($case): int => $case->value, $cases);
        $check($values === range(1, count($values)), 'not sequential');
    }
    $check(PlaybackRate::X1_5->factor() === 1.5 && PlaybackRate::X0_5->factor() === 0.5, 'factors');
    $check(PlaybackRate::X1->nextVoiceNoteRate() === PlaybackRate::X1_5 && PlaybackRate::X2->nextVoiceNoteRate() === PlaybackRate::X1, 'voice note cycle');
});

$test('play sends the queue and configuration in one native call', static function () use ($install, $check): void {
    $transport = $install();
    $transport->succeed('audio-player', 'next', [], DispatchMode::Deferred);
    $player = AudioPlayer::make('voice/1.m4a', 'vn-1')->queue('voice/2.m4a', 'https://cdn.example.com/3.m4a')
        ->rate(PlaybackRate::X1_5)->route(AudioRoute::Auto)->volume(0.8)->startAt(1500)->progressInterval(100)->play();
    $payload = Wire::decodeMap($transport->calls()[0]->payload);
    $check(json_decode($payload['sourcesJson'], true) === ['voice/1.m4a', 'voice/2.m4a', 'https://cdn.example.com/3.m4a'], 'sources');
    $check($payload['rate'] === 1.5 && $payload['route'] === 1 && $payload['startAt'] === 1500 && $payload['progressInterval'] === 100, 'config');
    $check(abs($payload['volume'] - 0.8) < 0.0001, 'volume');
    $check(AudioPlayer::current() === $player && $transport->calls()[1]->method === 'next', 'current/next');
    $player->stop();
    NativeTestHarness::uninstall();
});

$test('events update state, progress, queue index and end', static function () use ($install, $check): void {
    $transport = $install();
    $transport->succeed('audio-player', 'next', ['kind' => 2, 'state' => 3], DispatchMode::Deferred)
        ->succeed('audio-player', 'next', ['kind' => 1, 'position' => 500, 'duration' => 2000, 'buffered' => 2000, 'index' => 0], DispatchMode::Deferred)
        ->succeed('audio-player', 'next', ['kind' => 3, 'index' => 1], DispatchMode::Deferred)
        ->succeed('audio-player', 'next', ['kind' => 6, 'route' => 3], DispatchMode::Deferred)
        ->succeed('audio-player', 'next', ['kind' => 4], DispatchMode::Deferred);
    $log = [];
    $player = AudioPlayer::make('voice/a.m4a')->queue('voice/b.m4a')
        ->onStateChange(static function (PlaybackState $state) use (&$log): void { $log[] = 'state:'.$state->name; })
        ->onProgress(static function (PlaybackProgress $p) use (&$log): void { $log[] = 'progress:'.$p->fraction(); })
        ->onItemChange(static function (int $index, string $source) use (&$log): void { $log[] = "item:{$index}:{$source}"; })
        ->onRouteChange(static function (AudioRoute $route) use (&$log): void { $log[] = 'route:'.$route->name; })
        ->onEnd(static function (AudioPlayer $p) use (&$log): void { $log[] = 'end'; })
        ->play();
    $transport->flush();
    $check($log === ['state:Playing', 'progress:0.25', 'item:1:voice/b.m4a', 'route:Earpiece', 'end'], implode(',', $log));
    $check($player->state() === PlaybackState::Ended && $player->isStopped() && AudioPlayer::current() === null, 'ended');
    $transport->assertCalled('audio-player', 'next', 5);
    NativeTestHarness::uninstall();
});

$test('controls address the live player and live rate changes apply', static function () use ($install, $methods, $check, $throws): void {
    $transport = $install();
    $transport->succeed('audio-player', 'next', [], DispatchMode::Deferred);
    $player = AudioPlayer::make('voice/a.m4a')->queue('voice/b.m4a')->play();
    $player->pause()->seek(1200)->rate(PlaybackRate::X2)->route(AudioRoute::Speaker)->skipTo(1)->resume();
    $check($methods($transport) === ['play', 'next', 'pause', 'seek', 'setRate', 'setRoute', 'skipTo', 'resume'], implode(',', $methods($transport)));
    $check(Wire::decodeMap($transport->calls()[4]->payload)['rate'] === 2.0, 'rate payload');
    $throws(InvalidArgumentException::class, static fn () => $player->skipTo(5));
    $throws(LogicException::class, static fn () => $player->queue('voice/c.m4a'));
    $player->stop();
    $throws(LogicException::class, static fn () => $player->pause());
    NativeTestHarness::uninstall();
});

$test('playing a new player stops the current one', static function () use ($install, $methods, $check): void {
    $transport = $install();
    $transport->succeed('audio-player', 'next', [], DispatchMode::Deferred)->succeed('audio-player', 'next', [], DispatchMode::Deferred);
    $first = AudioPlayer::make('voice/a.m4a')->play();
    $second = AudioPlayer::make('voice/b.m4a')->play();
    $check($first->isStopped() && AudioPlayer::current() === $second, 'not replaced');
    $check($methods($transport) === ['play', 'next', 'stop', 'play', 'next'], implode(',', $methods($transport)));
    AudioPlayer::stopAll();
    $check(AudioPlayer::current() === null, 'stopAll');
    NativeTestHarness::uninstall();
});

$test('failures surface through onError', static function () use ($check): void {
    AudioPlayer::current()?->stop();
    $transport = NativeTestHarness::install();
    $transport->fail('audio-player', 'play', 'boom')->succeed('audio-player', 'next', ['kind' => 5, 'message' => 'Source error'], DispatchMode::Deferred)
        ->succeed('audio-player', 'next', [], DispatchMode::Deferred)->succeed('audio-player', 'stop');
    $errors = [];
    $player = AudioPlayer::make('voice/a.m4a')->onError(static function (string $message) use (&$errors): void { $errors[] = $message; })->play();
    $transport->flushOne();
    $check($errors === ['boom', 'Source error'] && $player->state() === PlaybackState::Failed, implode(',', $errors));
    $player->stop();
    NativeTestHarness::uninstall();
});

$test('sources must be URLs or sandbox-relative paths', static function () use ($throws, $check): void {
    foreach (['', '/etc/passwd', '../x.m4a', 'a/../../x', 'file:///x', 'content://media/1', 'a\\b'] as $source) {
        $throws(InvalidArgumentException::class, static fn () => AudioPlayer::make($source));
    }
    $check(AudioPlayer::make('https://cdn.example.com/a.m4a')->sources() === ['https://cdn.example.com/a.m4a'], 'url');
    $throws(InvalidArgumentException::class, static fn () => AudioPlayer::make('voice/a.m4a', 'bad id'));
});

$test('manifest targets PAM Native 1.0.35 with ExoPlayer', static function () use ($check): void {
    $plugin = json_decode((string) file_get_contents(dirname(__DIR__).'/pam-native.plugin.json'), true, flags: JSON_THROW_ON_ERROR);
    $check($plugin['pamNative'] === ['minimum' => '1.0.35', 'maximumExclusive' => '2.0.0'], 'range');
    $check(in_array('androidx.media3:media3-exoplayer:1.10.1', $plugin['android']['dependencies'], true), 'exoplayer');
    $check(in_array('android.permission.WAKE_LOCK', $plugin['android']['permissions'], true), 'wake lock');
});

$failed = 0;
foreach ($tests as $name => $body) {
    try {
        $body();
        fwrite(STDOUT, "PASS {$name}\n");
    } catch (Throwable $error) {
        $failed++;
        fwrite(STDERR, "FAIL {$name}: {$error->getMessage()}\n");
    }
}
fwrite(STDOUT, count($tests)." tests, {$failed} failures\n");
exit($failed === 0 ? 0 : 1);
