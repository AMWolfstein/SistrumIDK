---
name: graph-exploration
description: "Mandatory graph stage for production-code changes: locate symbols, callers, dependencies, tests and reuse candidates with CodeGraphContext, record verifiable evidence, and refresh after edits."
---

# Graph exploration

Applies to every task that changes production code; documentation-only tasks record
an exemption instead. Installing the tool is not evidence.

## Before editing

```sh
python3 workflow/codegraph/code_tools.py update
python3 workflow/codegraph/code_tools.py query --symbol <Symbol>
python3 workflow/codegraph/code_tools.py query --file <path>
python3 workflow/codegraph/code_tools.py tests impact --changed <path> ...
```

Establish: affected symbols/files, callers and callees, dependency and
cross-language boundaries, affected tests, existing implementations worth reusing,
and the expected impact scope. Read the source around each hit; a graph edge is a
pointer, not proof of runtime behaviour.

## After editing

Refresh the touched scope (`update --paths ...` or a full `update`), re-check changed
relationships, look for new duplicate implementations, unresolved references and
affected tests.

## Record

```sh
python3 workflow/work.py graph record --task <ID> \
  --query "callers of play -> Player.start, QueueService.next" \
  --query "tests impact -> PlayerTest" \
  --impact "androidApp/.../Player.kt" \
  --limitation "JNI externals are not linked by CGC; bridge.py used"
```

Keep evidence concise: executed queries, returned symbols, impact, limitations. Do
not dump full graph output. Evidence is tied to `HEAD` plus dirty-file hashes;
`task complete` rejects missing, stale or query-less evidence.

CodeGraphContext 0.6.13 covers Kotlin, Java, C and C++ symbols and calls with
EXTRACTED/INFERRED labels. It does not link JNI Kotlin externals to native symbols;
use `bridge.py` and `native_bindings.py` and treat those relationships as candidates.
Gradle, CMake, Android resources and shell remain adapter-backed. If the tool fails,
fall back to verified source inspection and record the failure as the limitation.
