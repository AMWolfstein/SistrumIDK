# Android review guidance

Use the sections that apply in the existing task review.

## API and module boundaries

- Before changing Media3, Room, Compose or Gradle APIs, read the app's pinned
  version from the version catalog or wrapper and check the corresponding
  official API reference and release notes. Record the source when behavior
  depends on a specific version.
- Inspect callers when changing a public contract in `sharedLogic`, `codecs`,
  `metadata`, `analysis` or `dsp`. Keep Kotlin/JVM libraries usable without
  Android dependencies where their existing contracts require it. Preserve
  decoder selection, audio fallback and JNI ownership at those boundaries.

## Kotlin and playback tests

- Check which component owns each coroutine and cancels it. Verify that rapid
  track changes, seek, service shutdown or a failed decode cannot let abandoned
  work publish stale playback state. Check Flow collection lifetime and ordering.
- Use the project's JUnit or `kotlin.test` and `kotlinx-coroutines-test`. Use
  `runTest` and scheduler control to verify ordering and cancellation when
  relevant. Assert observable state and cover error/fallback behavior with the
  narrowest applicable test.

## Compose and glass surfaces

- Use translucent glass selectively on floating controls, such as the
  mini-player. Group related playback actions in one surface. Keep library
  rows readable and reuse existing Haze or Backdrop primitives where they fit.
- If expanding into Now Playing, keep the artwork visually continuous. Check
  reduced-motion behavior and an opaque fallback for the glass surface.
- Use Compose previews with fixed artwork and player state for light, dark and
  Arabic RTL layouts. Review contrast over bright and busy covers, touch
  targets and TalkBack labels. For expensive effects, measure scroll or
  transition smoothness with playback on when device testing is available.

## File and account boundaries

- Review URI grants and lifetime when handling `content://` imports. Treat
  external metadata as input and check malformed or oversized values at the
  parser boundary. Check that Last.fm credentials and private file metadata
  stay out of logs and verification artifacts.

Ideas adapted from ECC's [Kotlin coroutines](https://github.com/affaan-m/ECC/tree/main/skills/kotlin-coroutines-flows),
[testing](https://github.com/affaan-m/ECC/blob/main/rules/kotlin/testing.md),
[documentation lookup](https://github.com/affaan-m/ECC/tree/main/skills/documentation-lookup),
[Android architecture](https://github.com/affaan-m/ECC/tree/main/skills/android-clean-architecture),
[security review](https://github.com/affaan-m/ECC/tree/main/skills/security-review),
[Liquid Glass](https://github.com/affaan-m/ECC/tree/main/skills/liquid-glass-design)
and [SwiftUI previews](https://github.com/affaan-m/ECC/tree/main/skills/swiftui-patterns).
