# Unified task lifecycle

One lifecycle applies to every agent (Codex, Claude Code, or any compatible tool).
Provider entry files point here; they do not restate it.

```
intake -> classify -> graph -> plan -> plan review -> implement ->
graph refresh -> verify -> review -> optimize -> accept -> commit
```

Proportionality is mandatory: a low-risk change must not be forced through the
expensive path, and a native/JNI/architectural change must not skip it.

## 1. Intake

Record the request, the baseline revision (`git rev-parse HEAD`), and the files or
symbols named by the requester. Read root `AGENTS.md` and
[`project-policy`](../workflow/README.md).

## 2. Scope and risk

| Risk | Examples | Required path |
| --- | --- | --- |
| Low | docs, comments, a local private helper | graph exemption or single focused query, targeted check |
| Medium | one module, shared UI component, test-only change | graph exploration, impact analysis, independent review |
| High | cross-module, JNI/bridge, native audio, build, security, destructive cleanup | graph exploration, plan review, independent review, explicit authorization for removals |

A task must define objective, scope, dependencies, acceptance criteria, affected
boundaries, verification commands and required evidence before implementation.

## 3. Graph exploration (mandatory for production-code changes)

Installing the tool or mentioning it is not evidence. Run real queries:

```sh
python3 workflow/codegraph/code_tools.py update
python3 workflow/codegraph/code_tools.py query --symbol <Symbol>
python3 workflow/codegraph/code_tools.py query --file <path>
python3 workflow/codegraph/code_tools.py tests impact --changed <path> ...
```

Before editing, establish: the affected symbols, callers/callees, cross-language
boundaries, affected tests, existing reusable implementations and expected impact.
After editing, refresh the affected scope and re-check relationships and duplicates.

Record the result (never a full graph dump):

```sh
python3 workflow/work.py graph record --task <ID> \
  --query "callers of play -> Player.start, QueueService.next" \
  --query "tests impact -> PlayerTest, QueueTest" \
  --impact "androidApp/.../Player.kt, QueueService.kt" \
  --limitation "CGC does not link JNI externals; bridge.py used for the native call"
```

Evidence is tied to `HEAD` plus content hashes of dirty files. `task complete`
rejects missing, stale or query-less evidence. A documentation-only task records an
exemption instead:

```sh
python3 workflow/work.py graph record --task <ID> --exempt "documentation-only change"
```

If the graph tool fails, fall back to verified source inspection and record the
failure as the limitation. Never claim a successful query that did not run.

## 4. Plan and plan review

The plan must show which existing implementation was inspected, why a new one is
needed, the dependency and boundary impact, measurable acceptance criteria and the
verification commands. For medium/high risk, an independent reviewer (or an explicit
separate review pass when no second agent exists) tries to reject it. Self-review is
not independent review. Stop review cycles that add no new evidence.

## 5. Implement, refresh, verify

Implement only the scoped change, then refresh the graph for the touched files and
verify changed relationships. Run the targeted checks; record executed, passed,
failed, skipped and unavailable results. Passing targeted tests never proves the
whole application correct.

## 6. Review and optimize

Independent review of the diff against the request and evidence. Optimization
follows KEEP / MERGE / REWRITE / REMOVE and is never automatic:

- REMOVE needs static references, graph relationships, runtime entry points,
  reflection, DI, JNI, resources, serialization, build files and tests checked.
- Zero graph callers are not proof of dead code; unresolved dynamic JNI is never dead.
- High-risk removals and rewrites need explicit task authorization.

## 7. Accept and commit

Completion requires: acceptance criteria met, graph evidence valid or exempt,
review PASS recorded, verification results recorded, unresolved high findings
addressed, and the diff inside the authorized scope.

```sh
python3 workflow/work.py task complete <ID> --reviewer <name> --round <1-3> --checks "<actual results>"
python3 workflow/work.py validate
```

One logical commit per task. Do not commit generated graph databases, caches,
credentials or machine paths. Push only when authorized.

## Enforcement limits

Gates are executable through `work.py`, which rejects missing/stale evidence. They
cannot stop an agent from editing files outside the workflow entirely; the claim is
bounded to the controlled path.
