# SOP: Bug-report workflow — confirm, trace, decide, fix

## Why this SOP exists

Every bug the user asks us to fix MUST be (a) reproduced on a clean
build before we touch production, and (b) locked against regression
forever after. Reproduction-first is non-negotiable: a fix we can't
demonstrate failing-then-passing is a guess. This SOP defines the
fixed pipeline from "here's a bug report" to "task closed".

It composes with `task-lifecycle.md` (status discipline, closure
checklist) and the `CLAUDE.md` bug-tracking rule (every confirmed
bug logged in `known-bugs-ledger.md`).

## Scope

Applies to any user-requested fix of a reported issue or bug
(GitHub issue, in-game report, user description). Does NOT apply to
greenfield coverage tasks (those follow `testing-principles.md`).

## Read this at session start

This SOP is required reading at the start of any session, and before
working any bug report. When you read it, **scan `.agent/tasks/` for
open tasks with `Type: Bug report — confirmed` and proactively offer
the user to fix them.** A confirmed-but-deferred bug must be
re-surfaced every session until it is fixed or the user explicitly
drops it. (We deliberately do NOT wire this into the Navigator
tooling — the obligation lives here, in required reading.)

## The pipeline

### Step 1 — Reproduction tests FIRST (before any production read-for-fix)

Write reproduction tests that fail against the **clean default build**
(current AR + the pinned libVulpes / ARLib on disk — the same
toolchain the agent builds with by default). Two hard rules:

1. **A client e2e (`testClient`) reproduction is MANDATORY.** The bug
   report describes player-visible behaviour, so the proof must live
   at the player-visible layer. Headless dev boxes run it under
   `xvfb-run` / a dedicated `DISPLAY` (see the testClient harness
   notes + `mcp-intellij-usage.md`).
2. **If the bug is also catchable in a server e2e (`testServer`),
   write that too.** The server test is the cheaper, faster
   regression guard; the client test is the player-truth guard. When
   both are possible, ship both.

Each reproduction test must:
- **Confirm the bug on the clean build** — it FAILS (or, where the
  bug is a silent no-op, asserts the wrong-but-current behaviour as a
  characterization) before any fix. Cache-bust per `flake-diagnosis.md`.
- **Survive as a regression guard** — after the fix it is EDITED to
  assert the corrected behaviour (Step 3 Path B), never deleted.

If a client e2e is genuinely impossible (no client-observable surface
at all), that is an EXCEPTION that must be stated explicitly in the
trace report and approved by the user — never skipped silently.

### Step 2 — Trace report (only after the behaviour is confirmed)

Once the reproduction confirms the reported behaviour, investigate
and deliver a structured trace report to the user covering:

- **(a) Cause** — what in the code produces the behaviour, cited to
  `file:line`, with the relevant gate / call path.
- **(b) Provenance** — when the bug was introduced: `git log` / blame
  archaeology, the commit or change that brought it in (or "present
  since inception"). Check upstream (dercodeKoenig / zmaster587) for
  existing fixes.
- **(c) Fix options** — there is usually more than one. Enumerate
  them with trade-offs (scope, risk, behavioural side-effects), and a
  recommendation.

Do NOT start fixing during Step 2. The report ends at the user's
decision point.

### Step 3 — User decides

The user picks one of two paths.

#### Path A — "form a task" (fix deferred)

Create a TASK file with these header fields:

```
- Type: Bug report — confirmed
- Priority: urgent
- Status: Backlog        # confirmed but unfixed = real task, not started, no blocker
- Created: <YYYY-MM-DD>
```

- Body assembled from the Step-2 trace report (cause / provenance /
  fix options).
- The reproduction tests are already shipped and committed; the task
  tracks only the production fix.
- Log the bug in `known-bugs-ledger.md` (Batch #2) and the README
  bug-ledger summary, per `CLAUDE.md`.

The `Type: Bug report — confirmed` header is what the session-start
scan (above) keys on. `Type` and `Priority` are NEW header fields
that sit alongside — not replace — the `task-lifecycle.md` status
enum; the status stays `Backlog` until the fix starts.

#### Path B — "fix now"

The user chooses one of the Step-2 fix options (or proposes their
own). Then:

- Implement the fix in production.
- **Edit the reproduction tests to assert the new, correct
  behaviour** — the failing / characterization test flips to a
  positive contract. Same tests, inverted polarity; never a parallel
  copy.
- Re-run both tiers green (cache-bust per `flake-diagnosis.md`).
- Close the task per the `task-lifecycle.md` closure checklist
  (status `Completed`, README + ledger sync, pyramid regen, EOD
  marker, commit). The ledger entry flips from "live" to "fixed by
  TASK-NN".

## Anti-patterns

- ❌ Reading production "to find the fix" before a reproduction test
  exists. Repro first, always.
- ❌ Shipping only a server test because the client harness is
  awkward. Client e2e is the mandatory player-truth guard; justify
  any exception explicitly and get approval.
- ❌ Deleting the reproduction test after the fix. It is the
  regression guard — flip its polarity, keep it.
- ❌ A confirmed bug with no ledger entry, or a deferred confirmed
  bug that the next session doesn't re-surface.
- ❌ Starting the fix before the user has chosen a fix option.

## Related

- `issue-reference-discipline.md` — how to write the issue number
  (never bare `#NN`; fully-qualify per the original issue link).
- `testing-principles.md` — contract-vs-impl test design.
- `flake-diagnosis.md` — cache-bust + distribution diagnosis.
- `task-lifecycle.md` — status values + closure checklist.
- `CLAUDE.md` bug-tracking rule + `history/known-bugs-ledger.md`.
