# PAM Native Audio: install and platform notes

> Moved out of the README; nothing was removed.
>
> Version ranges and permissions below are from the 0.2 README; the current values (PAM Native `>=1.46.0 <2.0.0`, `RECORD_AUDIO`, `NSMicrophoneUsageDescription`) are in the README, taken from `pam-native.plugin.json`.

## Install

```bash
pam composer require pushinbr/pam-native-audio
pam doctor --fix
```

The package is a PAM Native plugin discovered from Composer; nothing is added
to `pam-native.json`. It requires `pushinbr/pam-native` `>=1.0.35 <2.0.0` and
PHP 8.5.

### Android

Merged permissions: `INTERNET`, `ACCESS_NETWORK_STATE` (remote sources),
`MODIFY_AUDIO_SETTINGS` (communication mode for the earpiece) and `WAKE_LOCK`
(proximity screen-off). No runtime permission is needed. Dependencies:
`androidx.media3:media3-exoplayer`, `media3-datasource` and `media3-database`
`1.10.1`. The player requests transient audio focus, pauses when focus is lost
and when headphones are unplugged ("becoming noisy"). Remote audio is cached in
`cacheDir/pam-audio-cache` (64 MiB LRU).

### iOS

Frameworks `AVFoundation` and `CoreMedia`; no Info.plist keys and no usage
strings. The speaker route uses the `playback` category with the `spokenAudio`
mode; the earpiece route uses `playAndRecord` with `voiceChat` and enables
`UIDevice` proximity monitoring (the screen turns off at the ear). Playback
pauses on interruptions (a phone call) and when headphones are unplugged.

The package does not declare the `audio` background mode, so iOS suspends
playback when the app goes to the background unless another plugin (for
example `pam-native-calls`) or your host declares it.

### Sources

A source is either an `http(s)://` URL (≤ 4096 bytes) or a path **relative to
the PAM file sandbox** (≤ 1024 bytes, no leading `/`, no `..`, no `\`, no
scheme): `filesDir/pam-files` on Android, `Application Support/pam-files` on
iOS — the same space as `FileReference::$path`, the core `AudioRecorder` and
`pam-native-media`. A recorder `file://…/pam-files/voice/1.m4a` URI must be
reduced to `voice/1.m4a` before playing it.
