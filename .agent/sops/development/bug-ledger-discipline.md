# SOP: Bug ledger discipline

## Context

Read when you discover a production bug, or are deciding whether
something you found counts as one. The project requires every discovered
production bug to be logged AND pinned in the test suite. This SOP defines
what qualifies, how to pin it, and where it goes — formalising the rule
that lives in `CLAUDE.md`.

## What counts as a bug

A bug has a **player-visible (or caller-visible) consequence** — a wrong
behaviour a player, another mod, the save, or a public API can observe.
Examples that qualified: gravity controller defaulting station gravity to
0.1 (#3); a worn-tank pump that silently drains nothing on vanilla water
(#7); `getAcceleration` returning `NaN`/`Infinity` on a zero-weight
rocket (#8); dead tank/seat counters (#9).

## What is NOT a bug (impl-trivia)

Code that is "wrong" but has **no observable consequence today** is impl
trivia, not a ledger bug. Ledger entry #2 (`setStandTime(int)` ignored
its argument) was **dropped** precisely because its consequence was
"nothing observable" — the only caller passed the field value. Don't log
or pin impl-trivia; if you must note it, do so as a code comment, not a
ledger entry.

> Litmus: name the consequence in one sentence a player could notice. If
> you can't, it's not a ledger bug.

## How to log + pin

1. **Log** under the current batch in
   `.agent/history/known-bugs-ledger.md`: the symptom, the
   player-visible consequence, the `file:line` root cause, and how it was
   found. Number entries sequentially; numbering is stable (dropped
   entries stay struck-through so later numbers don't shift).
2. **Pin in the suite** one of two ways:
   - **Positive contract** — if you're fixing it now, assert the correct
     behaviour (the test would fail on the old code).
   - **Documents-known-bug** — if it stays open, pin the *current wrong*
     behaviour so a future fix flips the test deliberately. (The
     `_documentsKnownBug` method-name suffix is deprecated; a javadoc
     breadcrumb on a normally-named test is the current style.)
3. Keep the **live count** accurate: the ledger header arithmetic
   (opened − fixed − dropped) must match the open entries.

## Fixing an open bug

When a later task fixes a logged bug, flip its pin from documents-known
to a positive contract, mark the ledger entry ✅ FIXED with the fixing
task, and update the live count. This is part of task closure
([`task-lifecycle.md`](./task-lifecycle.md)).

## Prevention

- [ ] Consequence stated in one player-visible sentence (else it's not a
      bug).
- [ ] Logged in the ledger with root-cause `file:line`.
- [ ] Pinned (positive contract or documents-known-bug).
- [ ] Live count arithmetic updated.

## Related

- [`testing-principles.md`](./testing-principles.md),
  [`coverage-audit-playbook.md`](./coverage-audit-playbook.md),
  [`task-lifecycle.md`](./task-lifecycle.md), `CLAUDE.md`.
