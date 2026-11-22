# Future UI design references (approved direction, not implemented)

Recorded for the upcoming UI migration. Nothing here authorizes implementation in
this workflow task, and no upstream code may be copied before its licence and
attribution requirements are checked.

## BitChord — https://github.com/kushagrasinghx/BitChord

Primary reference for main screens, unified headers, header dimensions and button
proportions, progressive header blur, header scroll behaviour, Liquid Glass, floating
navigation, selection lens, mini player, player expansion/collapse transitions, Now
Playing layout, legacy animated player background, full-screen and animated artwork,
playback controls, queue, menus, dialogs and bottom sheets.

## Mornye — https://github.com/mornyeapp/Mornye

Primary reference for settings layout and subpages, section organization, controls and
visual hierarchy, lyrics layout, typography, scrolling, active-line animation,
word-by-word animation, unfocused lyrics blur and lyric transitions. The relevant
visual behaviour must be reimplemented natively in Jetpack Compose. No Flutter and
no Flutter dependency.

## Fixed Liquid Glass design

One fixed design, no selectable rendering themes:

- genuine Liquid Glass and progressive blur;
- full-screen player, album and playlist artwork;
- total playback duration;
- legacy animated player background and animated cover artwork;
- full animations and blend effects;
- visible lyrics with Mornye-style unfocused blur.

No appearance toggles for these decisions. Do not import alternate rendering modes,
player themes, artist signatures, Spotify Canvas or unrelated online features. Keep
technical fallbacks for unsupported devices without exposing them as user-selectable
glass modes. Preserve existing functional application settings.

## Constraints

Kotlin, Jetpack Compose, FFmpeg audio processing, AAudio output, JNI where required,
TagLib metadata, local database and library. No Flutter, libmpv, alternative playback
engines, streaming or online accounts. Inspect the actual upstream implementation and
licence before any reuse.
