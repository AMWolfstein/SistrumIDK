# Code navigation tools

Standalone **CodeGraph 1.6.2**, resource/Bash/native adapters, and optional
**Serena 2.1.0** verification. No tasks, planning, review gates, fingerprints,
hooks or application dependencies. Run commands from the repository root.

## Installation

Node/npm and Python 3.10+ are required for the graph adapter. Python 3.11+
is recommended; Serena 2.1.0 requires Python 3.11+.

```sh
npm ci --prefix workflow/codegraph --ignore-scripts --no-audit --no-fund
python3 -m venv .work/code-tools-python
.work/code-tools-python/bin/python -m pip install -r workflow/codegraph/requirements.txt
python3 workflow/codegraph/codegraph_adapter.py version
```

Optional semantic verifier, in a separate environment:

```sh
python3 -m venv .work/serena-python
.work/serena-python/bin/python -m pip install -r workflow/codegraph/requirements-serena.txt
export SERENA_PYTHON="$PWD/.work/serena-python/bin/python"
python3 workflow/codegraph/code_tools.py serena version
```

Dependencies never download during an ordinary graph update. Serena language
servers can download SDKs/server dependencies on first use; they require compatible
toolchains and network access. Language support is reported by Serena, not presumed.
No global agent/MCP configuration is installed. For Android use the project's JDK 21 and SDK.
Serena keeps its settings in the isolated home. Compiler checks and semantic
references cover different evidence.

## Update the map

```sh
export CODEGRAPH_SHELL_PYTHON="$PWD/.work/code-tools-python/bin/python"
python3 workflow/codegraph/code_tools.py update
```

One command collects CodeGraph symbols/relationships, structured resource links,
parse-only Bash evidence and JNI boundary candidates. Outputs live in ignored `.work/codegraph/`; the source checkout
is never executed by these graph/resource/shell collectors. CodeGraph runs on a
private source mirror. Missing Bash dependencies stay explicit coverage gaps.

Default inventory: existing tracked and unignored untracked source files reported
by Git, filtered to the supported extensions. Excludes `.git`, `.work`,
`node_modules`, `build`, `target`, `vendor`, `third_party`, and
`__pycache__` directories. The saved `scope` lists inputs and exclusions. Tests and
tracked generated sources are included. A deleted tracked file is ignored.

For a limited region (replaces, rather than merges with, the saved map):

```sh
python3 workflow/codegraph/code_tools.py update --paths \
  androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/components/MaterialSymbols.kt androidApp/src/main/cpp/ffmpeg_player.cpp
```

Resources currently cover JSON, XML and TOML. YAML and Gradle/CMake semantics
are unsupported. Native matching uses only simple enclosing JVM owners and literal
JNI names/macros; nested/overloaded/dynamic cases remain unresolved. Inspect Gradle and CMake configuration separately.

## Ask about a file or symbol

```sh
python3 workflow/codegraph/code_tools.py query --file androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/components/MaterialSymbols.kt
python3 workflow/codegraph/code_tools.py query --symbol MaterialSymbols --limit 30
```

Returns symbols, incoming/outgoing relationships, resource/native/channel evidence,
coverage and totals before truncation. Matching symbol names are exact (simple or
qualified); ambiguous matches remain separate. Definitions carry file/line locations.
The saved map is a snapshot: **run update after edits**. No fingerprint or hidden
freshness gate exists. Low coverage or absent references never proves dead code.

Optional language-server verification of the same indexed snapshot:

```sh
python3 workflow/codegraph/code_tools.py serena symbols --file androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/components/MaterialSymbols.kt
python3 workflow/codegraph/code_tools.py serena definition --file androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/components/MaterialSymbols.kt --symbol MaterialSymbols
python3 workflow/codegraph/code_tools.py serena references --file androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/components/MaterialSymbols.kt --symbol MaterialSymbols
```

Only those read operations are exposed. Serena uses a read-only project on the
private mirror, with dashboard/GUI disabled and an overall timeout (default 120s).
Its configuration/caches/logs default to `.work/serena/home`; `SERENA_HOME` can point
to another isolated home containing language-server settings. Do not run a graph
update and a Serena query concurrently on the same mirror. Partial snapshots can
miss dependencies; snapshot paths in Serena results must be mapped to the source
checkout. Files/toolchains absent from the snapshot produce limited semantic evidence.

## Low-level adapter and tests

The original `codegraph_adapter.py version` and `extract --request FILE --out FILE`
remain available. Requests contain `root` (absolute), `files` (relative source paths)
and optional `auxiliary` files. Default cache: `.work/codegraph/`; `CODEGRAPH_CACHE`
can override it with an absolute path. CLI discovery checks local `node_modules`,
then `.work/tools/node_modules`, then PATH; `CODEGRAPH_COMMAND` overrides with a
JSON argv list. CLI telemetry is disabled. Relationships retain extracted/inferred
labels; ABI signatures and runtime behavior are never claimed verified.

```sh
CODEGRAPH_SHELL_PYTHON="$PWD/.work/code-tools-python/bin/python" \
  python3 -m unittest discover -s workflow/codegraph -p 'test_*.py' -v
```

Tests include real CodeGraph parsing/incremental synchronization, resource/Bash
regressions, JNI false positives, input containment, navigation and the Serena read
allowlist. Missing real tools produce explicit skips. Real Serena/LSP smoke checks
are separate because SDK/server availability varies by machine.

## Dead code, optimization and simplification scans

The default audit combines code, test quality and coverage gaps in one report:

```sh
python3 workflow/codegraph/code_tools.py audit
python3 workflow/codegraph/code_tools.py audit --lang kotlin java cpp
python3 workflow/codegraph/code_tools.py audit --kind code --path androidApp/src/main
python3 workflow/codegraph/code_tools.py audit --kind tests --path sharedLogic
```

Default `--kind all` combines all five categories. `--kind code` selects the three
code categories; `--kind tests` selects test quality; `--kind gaps` selects coverage
hints. With no scope flags the CLI scans Kotlin, Java, C/C++, Python and shell
in the checkout. Use `--lang` to select specific languages. Repeat
`--path` for file/folder scopes, or use `--base REV` for changed files. `--all`
explicitly selects the checkout scope; it is distinct from `--kind all`.
Filters intersect. Reports and full runner logs go under ignored
`.work/audit/<kind>/report.md` and `report.json`; another scan of the same kind
replaces those files. Do not run the same kind concurrently in one checkout.
Standalone equivalent: `python3 workflow/codegraph/audit.py` with the same options.

Python uses `ruff check --output-format=json`
and shell uses `shellcheck --format=json1` on the selected files; both report
`UNAVAILABLE` until installed, and their unclassified checks stay gaps. Compiler
runners may analyze more files than
the selected scope; candidates are filtered to selected files. Android Lint can
write build caches.

`audit-config.json` contains the curated diagnostic categories and protected
paths. Generated/vendor code is protected. Source-only code kinds exclude test
paths; the combined default includes test paths. Suggestions
are **review candidates**, not safe automatic deletions or measured speedups.
Kotlin/Java use shared Android Lint; C++ uses clang-tidy with actual compilation
commands. These provide configuration-specific diagnostics, not exhaustive unused
public-symbol/reachability detection.

Exit status: `0` means no selected diagnostics or heuristic findings were emitted; `1` means candidates were found; `2` means incomplete coverage,
no matching files, or invalid invocation. Findings do not make an incomplete scan
complete. Compilation errors or nonzero commands without usable diagnostics never
produce a clean result. A clean scan does not establish that no dead code remains.


## Unified quality commands

Audit, Test Intelligence, Bridge Verification and Safe Optimization share
`quality.py`: schema `1.0.0` findings with stable IDs/fingerprints, execution
statuses `PASS`/`FINDINGS`/`UNAVAILABLE`/`INCOMPLETE`/`FAILED`/`SKIPPED`, bounded
subprocesses, content-keyed caches and JSON+Markdown reports. Reports are written
under ignored `.work/quality/`; `work.py quality ...` passes through to this CLI.

```sh
python3 workflow/codegraph/code_tools.py security --path androidApp
python3 workflow/codegraph/code_tools.py bridge discover
python3 workflow/codegraph/code_tools.py bridge verify --runtime-report runtime.json
python3 workflow/codegraph/code_tools.py bridge scorecard
python3 workflow/codegraph/code_tools.py tests inventory
python3 workflow/codegraph/code_tools.py tests quality
python3 workflow/codegraph/code_tools.py tests coverage
python3 workflow/codegraph/code_tools.py tests impact --changed androidApp/src/main/cpp/ffmpeg_player.cpp
python3 workflow/codegraph/code_tools.py tests flaky --command "./gradlew :sharedLogic:testAndroidHostTest" --repeat 3
python3 workflow/codegraph/code_tools.py optimize plan
python3 workflow/codegraph/code_tools.py optimize apply --plan .work/quality/optimization-plan.json --approve OPT-...
python3 workflow/codegraph/code_tools.py optimize compare --before old.json --after new.json
python3 workflow/codegraph/code_tools.py plan-review --feature specs/001-feature
python3 workflow/codegraph/code_tools.py cache status --namespace audit
```

Exit codes: `0` PASS, `1` FINDINGS/NEEDS_REVISION, `2` UNAVAILABLE/INCOMPLETE/FAILED/
BLOCKED/NO_FILES. An unavailable tool or skipped scope never yields `0`.

**Security.** `security` detects Semgrep and gitleaks and never
installs them. Semgrep runs only the bundled local `security-rules.yml` (or
`AUDIT_SEMGREP_CONFIG`), with registry/metrics disabled. Gitleaks uses `--no-git
--redact`; reports never contain secret values. Findings are triage candidates, not
proof of exploitability.

**Test Intelligence.** `inventory` parses Kotlin, Java and Python test declarations and records framework, location, category and
`NOT_EXECUTED` until an actual results file is supplied. `quality` reuses the existing
assertion/duplicate checks and adds review-only hints (over-mocking, fixed sleeps,
weak existence assertions, missing local Python imports). `coverage` reads existing LCOV line and branch data.
`flaky` repeats an explicit test command and records outcomes/runtime variance; it never
deletes or quarantines a test. `impact` uses the saved CodeGraph snapshot and expands
conservatively when dependencies are incomplete; targeted selection never replaces
the final full suite.

**Bridge Verification.** Discovery covers JNI declarations and implementations,
dynamic `RegisterNatives` and C ABI exports. Checks are `PASS`/`FAIL`/`NOT_APPLICABLE`/`NOT_VERIFIED`; contracts are
`VERIFIED`/`PARTIAL`/`UNVERIFIED`/`FAILED` with explicit platform/variant/ABI scope.
The scorecard reports discovery, static and runtime coverage separately; discovery
coverage is `UNKNOWN` without a reference inventory and is computed from independently
extracted declarations, not analyzer self-coverage. Runtime coverage counts only
actual supplied results — compilation is not runtime verification. Every contract's
optimization eligibility is `BLOCKED` unless all required checks pass, and even then
removal needs separate unreachability evidence. A default-scope run reports Android
`DevQa`/`arm64-v8a`; other variants remain unverified.

**Safe Optimization.** `optimize plan` produces evidence-linked recommendations for
tests (`KEEP`/`MERGE`/`REWRITE`/`REMOVE`/`ADD_COVERAGE`/`INVESTIGATE`) and production
code (`KEEP`/`SIMPLIFY`/`REFACTOR`/`MERGE`/`REMOVE`/`INVESTIGATE`). Bridge-reachable
symbols are always `KEEP`; production removal is always blocked. `optimize apply`
requires unchanged HEAD since planning, explicit `--approve` IDs, at most 10 items
per batch, a backup and optional verification commands; any failed verification
restores the backup. `APPLIED_NOT_VERIFIED` is used when no verification command ran.
Only same-file byte-identical duplicate test blocks have an executable operation;
everything else remains a review item.

**Planning review.** `plan-review` adds architecture (paths, duplicate symbols,
rollback), cross-language (reuses bridge reports; failed contract on a touched
boundary = BLOCKED) and test-strategy checks to `speckit-analyze`. It is deterministic,
read-only, runs no audit or native build, and adds no stage, agent or approval
loop. Missing evidence lowers confidence; it is never treated as success.

**Caching.** Analyzer results are cached under `.work/quality/cache/<namespace>/` and
keyed by selected file contents, configuration, tool version and analysis kind.
Unchanged inputs reuse the cache (`cache_hits` in the audit result); any change
invalidates it. `cache clear --namespace NAME` removes entries explicitly.

## Agent instructions

[Code navigation](../../.agents/skills/code-navigation/SKILL.md) describes graph
and Serena use; [audit](../../.agents/skills/audit/SKILL.md) covers report-only
scans and source verification. [Code-auditor](../../.agents/reviewers/code-auditor.md)
provides optional read-only review guidance. Root AGENTS.md links these explicitly;
automatic skill discovery depends on the agent. None restores planning/tasks,
review gates or automatic commits/pushes. Written prompts alone are not independent
reviews, and findings never authorize speculative deletion.


### Test quality and coverage inputs

Test inspection checks Kotlin and Java test
bodies for missing recognized assertions and exact-body duplicates. These are
review hints: helper assertions, expected exceptions, fixtures and parameters may
change the conclusion. Custom/parameterized/multiline syntax and C++ test bodies
are not fully supported and produce partial-inspection notices. Tests are never
automatically deleted or rewritten.

Gap inspection searches tests beyond the selected source scope for direct name
references. To import existing coverage, configure `coverage.<language>.lcov` in
`audit-config.json`. Without an input, coverage is reported as unavailable.
Repeated LCOV records aggregate hits; a run's exclusions and source revision are not verified.
Indirect tests and assertions must be inspected manually before adding tests or
claiming a behavioral gap.


### Android JVM and C++ runners

```sh
python3 workflow/codegraph/code_tools.py audit --lang kotlin java cpp
python3 workflow/codegraph/code_tools.py audit --kind code --lang kotlin java --path androidApp/src/main
python3 workflow/codegraph/code_tools.py audit --kind optimization --lang cpp --path androidApp/src/main/cpp
```

Activate the project's JDK 21, Android SDK and NDK environment first.
Kotlin/Java share one `:androidApp:lintDevQa` Gradle invocation, using a checked-in
init script to enable all warnings and test-source checking, and write fresh XML
with absolute paths. The root `gradlew` is preferred, with `gradle` on PATH as a
fallback. If neither is available the runner reports `UNAVAILABLE`. Gradle Kotlin
DSL (`.kts`) is not analyzed. The effective test-source setting is logged and
verified; missing/false confirmation makes coverage INCOMPLETE. App
Gradle sources are unchanged. A private per-run report path prevents stale-report
reuse; logs stream to `.work/audit/<kind>/android.log`. Gradle may compile Android dependencies. No assemble/APK task, test execution or auto-fix
is requested. Missing wrapper/toolchain, Gradle failure or Lint internal errors are
INCOMPLETE. Ordinary Lint code findings are candidates, not analyzer crashes.

The configured roots cover the app's main, DevQa and matching test source sets.
Files in other variants and Kotlin modules are reported outside this Lint scope.
Use the project's Gradle tasks for those modules. Gradle `.kts` scripts are outside
this analysis. Both JVM language
configurations must agree to share a run. Existing suppressions/baselines can limit
emitted diagnostics. Lint is useful for Android API/performance/simplification
patterns; it does not prove that arbitrary unused public Kotlin/Java declarations
are dead. Raw XML/logs retain diagnostics outside the curated categories; tool
metadata counts those as `unclassified_diagnostics`.

C++ default discovery selects an existing QA arm64-v8a
`compile_commands.json` from the configured `androidApp/.cxx` locations. CMake
configuration for the legacy JNI player requires the project's FFmpeg files. To select another real configuration or executable:

```sh
export AUDIT_CPP_COMPILATION_DATABASE=/absolute/path/to/compile_commands.json
export AUDIT_CLANG_TIDY=/absolute/path/to/clang-tidy
```

A database directory is accepted too; alternate filenames are rejected because
clang-tidy reads the canonical filename. No database is fabricated or configured
automatically. `clang-tidy` comes from the override, PATH or activated NDK; there
is no runtime download. Selected translation units use the database's actual flags;
missing compile commands (including standalone headers/test targets) are explicit
PARTIAL/INCOMPLETE coverage. Headers are inspected as included, without claiming
that every header was reached. Stale build directories, absent generated headers,
compiler errors and timeouts stay incomplete. Only diagnostics in selected files
are classified; no fixes or native linkage changes are applied.
