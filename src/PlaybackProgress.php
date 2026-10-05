<?php

declare(strict_types=1);

namespace Pam\Native\Audio;

final readonly class PlaybackProgress
{
    public function __construct(
        public int $positionMillis,
        public int $durationMillis,
        public int $bufferedMillis,
        public int $index,
    ) {
    }

    /** @param array<string, string|int|float|bool> $values */
    public static function fromWire(array $values): self
    {
        return new self(
            max(0, (int) ($values['position'] ?? 0)),
            max(0, (int) ($values['duration'] ?? 0)),
            max(0, (int) ($values['buffered'] ?? 0)),
            max(0, (int) ($values['index'] ?? 0)),
        );
    }

    public function fraction(): float
    {
        return $this->durationMillis > 0 ? min(1.0, $this->positionMillis / $this->durationMillis) : 0.0;
    }
}
