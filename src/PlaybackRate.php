<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

/** Playback speed presets (pitch is preserved). */
enum PlaybackRate: int
{
    case X0_5 = 1;
    case X0_75 = 2;
    case X1 = 3;
    case X1_25 = 4;
    case X1_5 = 5;
    case X1_75 = 6;
    case X2 = 7;

    public function factor(): float
    {
        return match ($this) {
            self::X0_5 => 0.5,
            self::X0_75 => 0.75,
            self::X1 => 1.0,
            self::X1_25 => 1.25,
            self::X1_5 => 1.5,
            self::X1_75 => 1.75,
            self::X2 => 2.0,
        };
    }

    /** Cycles 1× → 1.5× → 2× → 1×, the usual voice-note toggle. */
    public function nextVoiceNoteRate(): self
    {
        return match ($this) {
            self::X1 => self::X1_5,
            self::X1_5 => self::X2,
            default => self::X1,
        };
    }
}
