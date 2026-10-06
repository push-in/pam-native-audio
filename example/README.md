# Voice notes demo

A one-screen PAM Native app that plays a queue of three voice notes with
`pushinbr/pam-native-audio`: play/pause, seek, speed toggle, earpiece routing
and native auto-advance.

```bash
cd example
pam composer install
pam doctor --fix
pam dev            # or: pam build
```

The app installs the released package from Packagist and streams public ExoPlayer test media over https; replace
`VoiceNotesDemo::NOTES` with your own URLs or sandbox-relative paths. Hold the
phone to your ear while a note plays to hear the earpiece route.
