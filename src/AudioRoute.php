<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

/** Output routing: Auto switches to the earpiece while the phone is held at the ear (proximity sensor). */
enum AudioRoute: int
{
    case Auto = 1;
    case Speaker = 2;
    case Earpiece = 3;
}
