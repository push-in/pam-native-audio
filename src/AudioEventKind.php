<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

/** Events pushed by the native player through the module event channel. */
enum AudioEventKind: int
{
    case Progress = 1;
    case State = 2;
    case ItemChanged = 3;
    case Ended = 4;
    case Failure = 5;
    case Route = 6;
}
