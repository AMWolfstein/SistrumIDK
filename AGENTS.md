# Project instructions

Read and follow [workflow/README.md](workflow/README.md) for the shared project
workflow and applicable review guidance. Start there in any coding agent.

Non-negotiable for production-code changes:

- Follow the [unified task lifecycle](workflow/lifecycle.md).
- Explore the code graph with real queries before editing, and record the evidence:
  `python3 workflow/work.py graph record --task <ID> ...`. `task complete` rejects
  missing or stale evidence.
- UI work follows [workflow/ui-architecture.md](workflow/ui-architecture.md); the
  approved future visual direction is in
  [workflow/design-references.md](workflow/design-references.md) and must not be
  implemented without a separate authorized task.
- No APK/AAB builds, FFmpeg upgrades or unrelated application changes as part of a
  workflow-only task.
