<?php

declare(strict_types=1);

use Pam\Native\Audio\AudioRecorder;
use Pam\Native\Audio\AudioRecording;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;

require __DIR__.'/contract-harness.php';

$recording = null;
$audioRequest = AudioRecorder::stop(
    static function (AudioRecording $value) use (&$recording): void {
        $recording = $value;
    },
);
$assert(
    TestDiagnostics::$moduleCall !== null
        && TestDiagnostics::$moduleCall['requestId'] === $audioRequest
        && TestDiagnostics::$moduleCall['module'] === 'audio-recorder'
        && TestDiagnostics::$moduleCall['method'] === 'stop',
    'Audio recorder facade did not emit its typed native module call.',
);
Runtime::dispatchModuleResult(
    $audioRequest,
    ModuleResultStatus::Success->value,
    Wire::map([
        'uri' => 'file:///tmp/pam-voice-test.m4a',
        'relativePath' => 'recordings/pam-voice-test.m4a',
        'fileName' => 'pam-voice-test.m4a',
        'mimeType' => 'audio/mp4',
        'durationMs' => 2_400,
        'size' => 19_200,
    ]),
);
$assert(
    $recording instanceof AudioRecording
        && $recording->durationMs === 2_400
        && $recording->size === 19_200
        && $recording->relativePath === 'recordings/pam-voice-test.m4a'
        && $recording->mimeType === 'audio/mp4',
    'Audio recorder facade did not decode the native recording.',
);

$audioFailure = null;
$audioStartRequest = AudioRecorder::start(
    static function (): void {
        throw new RuntimeException('Failed audio start must not invoke success.');
    },
    static function (string $message) use (&$audioFailure): void {
        $audioFailure = $message;
    },
);
Runtime::dispatchModuleResult(
    $audioStartRequest,
    ModuleResultStatus::Failure->value,
    'Microphone is unavailable.',
);
$assert(
    $audioFailure === 'Microphone is unavailable.',
    'Audio recorder failures must reach the optional failure callback.',
);

// An interruption (incoming call) ends the watch with the partial recording.
$progress = [];
$partial = false;
AudioRecorder::watch(
    static function ($value) use (&$progress): void { $progress[] = $value->durationMs; },
    100,
    null,
    static function (?AudioRecording $value) use (&$partial): void { $partial = $value; },
);
$watchRequest = TestDiagnostics::$moduleCall['requestId'];
Runtime::dispatchModuleResult($watchRequest, ModuleResultStatus::Success->value, Wire::map(['subscription' => 9]));
$nextRequest = TestDiagnostics::$moduleCall['requestId'];
$assert(TestDiagnostics::$moduleCall['method'] === 'next', 'Recorder watch must read its first event.');
Runtime::dispatchModuleResult($nextRequest, ModuleResultStatus::Success->value, Wire::map(['durationMs' => 300, 'amplitude' => 0.5]));
$nextRequest = TestDiagnostics::$moduleCall['requestId'];
Runtime::dispatchModuleResult($nextRequest, ModuleResultStatus::Success->value, Wire::map([
    'interrupted' => true,
    'uri' => 'file:///tmp/pam-voice-partial.m4a',
    'relativePath' => 'recordings/pam-voice-partial.m4a',
    'fileName' => 'pam-voice-partial.m4a',
    'mimeType' => 'audio/mp4',
    'durationMs' => 4_100,
    'size' => 30_000,
]));
$assert(
    $progress === [300]
        && $partial instanceof AudioRecording
        && $partial->durationMs === 4_100
        && TestDiagnostics::$moduleCall['method'] === 'unwatch',
    'An interruption must deliver the partial recording and end the watch.',
);

echo "audio recorder: {$tests} assertions passed\n";
