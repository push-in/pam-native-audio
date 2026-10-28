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

echo "audio recorder: {$tests} assertions passed\n";
