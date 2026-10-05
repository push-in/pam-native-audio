<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

/** Native player state. */
enum PlaybackState: int
{
    case Idle = 1;
    case Buffering = 2;
    case Playing = 3;
    case Paused = 4;
    case Ended = 5;
    case Failed = 6;
}
