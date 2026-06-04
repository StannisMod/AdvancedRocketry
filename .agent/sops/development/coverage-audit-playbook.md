# SOP: Running a coverage audit & triaging gaps

## Context

Read before running a "find what's untested" sweep or acting on an audit
doc. Audits (e.g. `.agent/audits/2026-05-27-full-coverage-audit.md`,
TASK-37…44) generate long gap lists; most of the value is in **triaging**
them correctly, not in writing a test for every line. Writing tests for
non-contracts inflates the suite and locks in implementation.

## Classify every gap before writing anything

Per [`testing-principles.md`](./testing-principles.md):

- **Contract gap** — a player-visible behaviour / public API / save-wire
  format is genuinely unpinned → write a test.
- **Impl-only gap** — the "gap" is an internal helper, magic number, or
  loop bound with no observable surface → **drop it** (record why).
- **Wrong-framing gap** — the audit assumed a behaviour that the code
  doesn't actually have (e.g. "pump should lift vanilla water" when it
  only drains `IFluidBlock`) → drop or reframe to the real contract,
  often logging a bug instead (ledger #7).

## Collapse discipline

- Aggressively drop impl-only and unwired gaps — a dropped gap with a
  one-line reason beats a brittle test. The TASK-40c batch saved ~28h by
  collapsing 10 audited gaps to the 2 real contracts.
- Don't pin a gap whose fixture cost dwarfs its value just to raise a
  count — defer it as a TASK with the cost noted.

## Shallow → deep conversion

When an existing test only smoke-checks ("assembles to a live entity"),
the audit's job is to deepen it to the actual contract ("a worn motor
raises breaking probability", "a placed drill yields drillingPower>0"),
reusing the existing fixture. Prefer deepening over net-new classes.

## Residuals become TASKs, never free-form lists

Every deferred gap lands as a `TASK-NN-*.md` with a plan + blocker +
acceptance — free-form "things to do later" bullets are forbidden outside
TASK files (see [`task-lifecycle.md`](./task-lifecycle.md)). Note the
recommended landing order when gaps interact.

## Count contract-coverage, not pins

Report "N player-visible behaviours now pinned", not "N new asserts".
Resist tightening with magic-number assertions during the audit.

## Log what you dropped

Silent truncation reads as "covered everything". For every dropped gap,
record the gap id and the one-line reason (impl-only / unwired /
wrong-framing) in the task doc.

## Prevention

- [ ] Every gap classified contract / impl-only / wrong-framing before
      coding.
- [ ] Impl-only & unwired gaps dropped with a logged reason.
- [ ] Deepened existing tests where possible instead of new classes.
- [ ] Deferrals filed as TASKs, not bullet lists.

## Related

- [`testing-principles.md`](./testing-principles.md),
  [`task-lifecycle.md`](./task-lifecycle.md),
  [`bug-ledger-discipline.md`](./bug-ledger-discipline.md).
