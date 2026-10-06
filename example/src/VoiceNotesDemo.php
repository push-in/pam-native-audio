<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Audio\AudioPlayer;
use Pam\Native\Audio\AudioRoute;
use Pam\Native\Audio\PlaybackProgress;
use Pam\Native\Audio\PlaybackRate;
use Pam\Native\Audio\PlaybackState;
use Pam\Native\Align;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Style;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

final class VoiceNotesDemo extends Component
{
    /** Public ExoPlayer test media; replace with your own https URLs or sandbox paths. */
    private const array NOTES = [
        'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3',
        'https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3',
        'https://storage.googleapis.com/exoplayer-test-media-0/play.mp3',
    ];

    private ?AudioPlayer $player = null;
    private PlaybackRate $rate = PlaybackRate::X1;
    private ?int $playingIndex = null;
    private PlaybackState $state = PlaybackState::Idle;
    private int $positionMs = 0;
    private int $durationMs = 0;
    private string $route = 'speaker';
    private string $error = '';

    public function render(): Element
    {
        $rows = [];
        foreach (self::NOTES as $index => $_source) {
            $active = $index === $this->playingIndex;
            $rows[] = Row::make(
                Button::make($active && $this->state === PlaybackState::Playing ? 'Pause' : 'Play')
                    ->onPress(fn () => $this->toggle($index)),
                Text::make(sprintf(
                    'Note %d  %s',
                    $index + 1,
                    $active ? self::clock($this->positionMs).' / '.self::clock($this->durationMs) : '',
                )),
            )->style(new Style(gap: 12, alignItems: Align::Center));
        }

        return Screen::make(
            SafeAreaView::make(
                Column::make(
                    Text::make('Voice notes')->style(new Style(fontSize: 24, fontWeight: 700)),
                    ...[
                        ...$rows,
                        Row::make(
                            Button::make(sprintf('%.2g×', $this->rate->factor()))->onPress($this->cycleRate(...)),
                            Button::make('-5 s')->onPress(fn () => $this->seekBy(-5_000)),
                            Button::make('+5 s')->onPress(fn () => $this->seekBy(5_000)),
                            Button::make('Stop')->onPress($this->stop(...)),
                        )->style(new Style(gap: 8)),
                        Text::make('State: '.$this->state->name.' · route: '.$this->route),
                        $this->error !== '' ? Text::make('Error: '.$this->error) : null,
                    ],
                )->style(new Style(flexGrow: 1, padding: 24, gap: 12)),
            ),
        );
    }

    public function toggle(int $index): void
    {
        if ($this->player !== null && !$this->player->isStopped() && $index === $this->playingIndex) {
            $this->player->toggle();

            return;
        }
        $this->start($index);
    }

    public function cycleRate(): void
    {
        $this->rate = $this->rate->nextVoiceNoteRate();
        if ($this->player !== null && !$this->player->isStopped()) {
            $this->player->rate($this->rate);
        }
    }

    public function seekBy(int $deltaMs): void
    {
        if ($this->player !== null && !$this->player->isStopped()) {
            $this->player->seek(max(0, $this->positionMs + $deltaMs));
        }
    }

    public function stop(): void
    {
        AudioPlayer::stopAll();
        $this->player = null;
        $this->playingIndex = null;
        $this->state = PlaybackState::Idle;
    }

    private function start(int $index): void
    {
        $this->error = '';
        $this->playingIndex = $index;
        $this->positionMs = 0;
        $this->durationMs = 0;
        // The following notes play natively, without a PHP round trip.
        $this->player = AudioPlayer::make(self::NOTES[$index])
            ->queue(...array_slice(self::NOTES, $index + 1))
            ->rate($this->rate)
            ->route(AudioRoute::Auto)
            ->onProgress(function (PlaybackProgress $progress, AudioPlayer $player) use ($index): void {
                if ($player === $this->player) {
                    $this->playingIndex = $index + $progress->index;
                    $this->positionMs = $progress->positionMillis;
                    $this->durationMs = $progress->durationMillis;
                }
            })
            ->onStateChange(function (PlaybackState $state, AudioPlayer $player): void {
                if ($player === $this->player) {
                    $this->state = $state;
                }
            })
            ->onItemChange(function (int $queueIndex, string $_source, AudioPlayer $player) use ($index): void {
                if ($player === $this->player) {
                    $this->playingIndex = $index + $queueIndex;
                }
            })
            ->onRouteChange(function (AudioRoute $route): void {
                $this->route = strtolower($route->name);
            })
            ->onEnd(function (AudioPlayer $player): void {
                if ($player === $this->player) {
                    $this->stop();
                }
            })
            ->onError(function (string $message): void {
                $this->error = $message;
            })
            ->play();
    }

    private static function clock(int $millis): string
    {
        $seconds = intdiv(max(0, $millis), 1000);

        return sprintf('%d:%02d', intdiv($seconds, 60), $seconds % 60);
    }
}
