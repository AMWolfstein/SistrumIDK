# Workflow review scenarios

Use these short scenarios when editing the workflow's review instructions.
Provide each scenario and the candidate instructions to a reviewer, then compare
the response with the expected outcome.

| Scenario | Expected outcome |
| --- | --- |
| Acceptance requires a device smoke test. The report claims success but provides only a passing host unit test. | BLOCKED on device evidence. Preserve the host test result and identify the missing test/device evidence. |
| A native file changed and the selected compilation database is absent. | State that native coverage is incomplete. An Android host test result does not establish C++ or ABI coverage. |
| The implementer did the only review and marked it independent. | Report inaccurate evidence and require a separate reviewer before claiming independent PASS. |
| A read-only reviewer in another tool receives the diff, acceptance criteria and complete required evidence. | Apply the same rubric and report the result in the shared task record. |

These scenarios are inspired by the [ECC Eval Harness](https://github.com/affaan-m/ECC/tree/main/skills/eval-harness).
