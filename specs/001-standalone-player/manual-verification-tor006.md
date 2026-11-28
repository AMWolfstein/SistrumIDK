# TOR006 manual playback verification session

Everything that can be measured automatically is covered by `PlaybackOutputTest`
(eight containers rendered through AAudio with 0 underruns, audible position
progress, seek accuracy, crossfade) plus the unit-tested focus/route helpers
(`audioFocusChangeAction`, `audioOutputDisconnectRequiresRecovery`,
`queueForAvailableTracks`). The checks below need real listening or a physical
interaction and are therefore **not** claimed as verified until you run them.

Device: OnePlus CPH2307, Android 15. Use the installed dev build
(`com.amwolfstein.sistrum.dev`) and the existing `/Music` library.

| # | Check | Exact steps | Expected behaviour | Evidence I collect |
|---|---|---|---|---|
| 1 | Audible output | Play any FLAC from the library, volume ~50 %, wired headphones plugged in. | Sound comes from the headphones only, no clicks, no dropouts over 60 s. | `adb logcat -d -s SistrumPlayback` snippet with `state=PLAYING`, plus your confirmation line "heard, clean". |
| 2 | Format sweep | Play one track of each: MP3, FLAC, M4A/AAC, Opus, Ogg Vorbis, WavPack, WAV, AIFF. | Every file starts within ~1 s, plays to the end, seek forward/back works. | Per-file confirmation; I attach the automatic underrun matrix from `PlaybackOutputTest`. |
| 3 | Seek accuracy | In a 3-minute track, seek to 50 %, then to 10 %, then back to 90 %. | Position label matches the request within ~1 s; audio restarts at the right place. | Screenshot of the position slider after each seek; your confirmation of the audible jump. |
| 4 | Gapless album | Enable repeat-off, play a live/continuous album (no gap between tracks). | The next track starts without silence or a click. | Your confirmation; `SistrumPlayback` log lines with `Consumed native transition=GaplessPromoted`. |
| 5 | Crossfade | Settings → playback, crossfade 3 s, play two tracks. | Gain fades out/in over ~3 s, no click, position counter keeps moving. | Log lines `CrossfadeStarted`/`CrossfadeCompleted` and your confirmation. |
| 6 | Focus loss / gain | Play a track, then start another music app (or a call via a second phone). | Ours pauses or ducks as configured; on focus gain it resumes/restores volume. | `dumpsys audio` focus stack before/after + your confirmation. |
| 7 | Route change | While playing, unplug the headphones, then plug them back. | Playback pauses on unplug (becoming-noisy) and resumes at the same position when output returns. | `SistrumPlayback` lines `Audio output changed` and your confirmation. |
| 8 | Bluetooth | Connect a Bluetooth headset, play, then use its play/pause/next buttons. | Audio routes to the headset; hardware buttons control playback; metadata shows on the headset. | `dumpsys bluetooth_manager` route entry, `dumpsys media_session` state, your confirmation. |
| 9 | Background + notification | Play, press Home, then use the notification controls (pause/next/previous). | Service keeps playing; controls work while the app is backgrounded. | `dumpsys activity services` entry and your confirmation. |
| 10 | Process death restore | Play track 5 of a queue, pause, swipe the app away, reopen. | The same queue and the paused position are restored; unavailable files are dropped. | `adb shell am force-stop` + relaunch log with `Unable to restore` absent, screenshot of the restored queue. |
| 11 | Analysis during playback | Start "Analyze library" in Settings, keep a track playing. | No stutter or underrun; analysis progresses in the background. | `PlaybackOutputTest` underrun baseline plus log timestamps of analysis vs playback. |

## How I will record it

- I run the automated suites first and attach their counts.
- For each manual item you confirm, I add one line to `test-matrix.md` with the
  date, device and the observed result; items not run stay `UNVERIFIED`.
- No item above is marked verified on the strength of a passing build.
